import { HttpErrorResponse } from '@angular/common/http';

import { Problem } from './api.models';

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Email o contraseña incorrectos.',
  EMAIL_ALREADY_REGISTERED: 'Ya existe una cuenta con ese email.',
  UNSUPPORTED_MEDIA_TYPE: 'Formato no soportado. Usa PDF, PNG, JPEG, TIFF, WEBP, DOCX o TXT.',
  PAYLOAD_TOO_LARGE: 'El archivo supera el tamaño máximo permitido (20 MB).',
  RATE_LIMIT_EXCEEDED: 'Demasiadas solicitudes seguidas. Espera un momento e inténtalo de nuevo.',
  INVALID_STATUS_TRANSITION: 'La operación no está permitida en el estado actual del documento.',
  DOCUMENT_NOT_FOUND: 'El documento no existe o no tienes acceso a él.',
  SERVICE_UNAVAILABLE: 'El servicio no está disponible en este momento.',
};

/** Mensaje legible a partir de un error de la API (formato RFC 7807). */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'No se pudo conectar con el servidor.';
    }
    const problem = error.error as Partial<Problem> | null;
    if (problem?.code === 'VALIDATION_FAILED' && problem.errors?.length) {
      return problem.errors.map((e) => `${e.field}: ${e.message}`).join(' · ');
    }
    if (problem?.code && MESSAGES[problem.code]) {
      return MESSAGES[problem.code];
    }
    if (problem?.detail) {
      return problem.detail;
    }
  }
  return 'Ha ocurrido un error inesperado.';
}
