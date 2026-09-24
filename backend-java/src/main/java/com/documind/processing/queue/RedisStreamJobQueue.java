package com.documind.processing.queue;

import com.documind.processing.ProcessingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cola de jobs sobre Redis Streams con un grupo de consumidores.
 * <ul>
 *   <li>Cada mensaje lo recibe un solo consumidor del grupo, aunque haya varias instancias de la API.</li>
 *   <li>El ACK se envía después de procesar: si la instancia muere a mitad, el mensaje queda pendiente
 *       y {@link #requeueAbandoned()} lo vuelve a encolar.</li>
 *   <li>El stream se recorta a {@code maxLength} entradas para que no crezca sin límite.</li>
 * </ul>
 */
@Component
public class RedisStreamJobQueue implements ProcessingJobQueue, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamJobQueue.class);

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final ProcessingWorker worker;
    private final QueueProperties properties;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    public RedisStreamJobQueue(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                               ProcessingWorker worker, QueueProperties properties) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.worker = worker;
        this.properties = properties;
    }

    @Override
    public void publish(ProcessingJob job) {
        RecordId id = redis.opsForStream().add(StreamRecords.newRecord()
                .in(properties.stream())
                .ofMap(job.toMap()));
        log.info("Processing job {} published for document {} (message {})", job.processingId(), job.documentId(), id);
    }

    // ---- Consumo ----

    @Override
    public void start() {
        createGroupIfMissing();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(properties.pollTimeout())
                        .batchSize(1)
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        for (int i = 0; i < properties.concurrency(); i++) {
            Consumer consumer = Consumer.from(properties.group(), properties.consumerName() + "-" + i);
            container.register(StreamReadRequest.builder(StreamOffset.create(properties.stream(), ReadOffset.lastConsumed()))
                    .consumer(consumer)
                    .autoAcknowledge(false)
                    // Un fallo puntual de Redis no debe cancelar la suscripción: se sigue leyendo al recuperarse
                    .cancelOnError(error -> false)
                    .errorHandler(error -> log.warn("Error reading the job stream: {}", error.getMessage()))
                    .build(), this::onMessage);
        }
        container.start();
        running = true;
        log.info("Job consumer started: stream={}, group={}, workers={}", properties.stream(), properties.group(),
                properties.concurrency());
    }

    private void onMessage(MapRecord<String, String, String> message) {
        try {
            worker.handle(ProcessingJob.fromMap(message.getValue()));
        } catch (RuntimeException ex) {
            // El worker ya deja el procesamiento en FAILED; aquí solo se evita perder el hilo del consumidor
            log.error("Unexpected error handling job message {}", message.getId(), ex);
        } finally {
            acknowledge(message.getId());
        }
    }

    private void acknowledge(RecordId id) {
        try {
            redis.opsForStream().acknowledge(properties.stream(), properties.group(), id);
        } catch (DataAccessException ex) {
            // Sin ACK el mensaje se reencolará más tarde; el worker lo ignorará porque ya no está en cola
            log.warn("Could not acknowledge job message {}: {}", id, ex.getMessage());
        }
    }

    private void createGroupIfMissing() {
        try {
            // MKSTREAM: crea el stream vacío si todavía no existe
            redis.execute(connection -> connection.streamCommands().xGroupCreate(
                    properties.stream().getBytes(StandardCharsets.UTF_8), properties.group(),
                    ReadOffset.from("0"), true), true);
        } catch (RedisSystemException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("BUSYGROUP")) {
                throw ex;
            }
            // El grupo ya existe (arranques posteriores o varias instancias)
        }
    }

    /**
     * Vuelve a encolar los mensajes entregados hace demasiado tiempo sin ACK (su consumidor murió
     * antes de terminar). Se reclaman primero para que otro consumidor no los procese a la vez.
     */
    public int requeueAbandoned() {
        PendingMessages pending = redis.opsForStream().pending(properties.stream(), properties.group(),
                Range.unbounded(), 100);
        String recoverer = properties.consumerName() + "-recovery";
        int requeued = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.abandonedAfter()) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(properties.stream(),
                    properties.group(), recoverer, properties.abandonedAfter(), message.getId());
            for (MapRecord<String, Object, Object> record : claimed) {
                Map<String, String> values = new HashMap<>();
                record.getValue().forEach((key, value) -> values.put(key.toString(), value.toString()));
                publish(ProcessingJob.fromMap(values));
                acknowledge(record.getId());
                requeued++;
            }
        }
        return requeued;
    }

    /** Recorta el stream: los mensajes ya confirmados no se vuelven a leer. */
    public void trim() {
        redis.opsForStream().trim(properties.stream(), properties.maxLength(), true);
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
