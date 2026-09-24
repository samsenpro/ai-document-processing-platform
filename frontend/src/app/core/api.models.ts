export type DocumentStatus = 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type DocumentType = 'INVOICE' | 'CONTRACT' | 'RESUME' | 'RECEIPT' | 'IDENTIFICATION' | 'REPORT' | 'OTHER';
export type RequestedDocumentType = 'AUTO' | DocumentType;
export type ProcessingStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export const DOCUMENT_TYPES: DocumentType[] = [
  'INVOICE', 'CONTRACT', 'RESUME', 'RECEIPT', 'IDENTIFICATION', 'REPORT', 'OTHER',
];

export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  INVOICE: 'Factura',
  CONTRACT: 'Contrato',
  RESUME: 'Hoja de vida',
  RECEIPT: 'Recibo',
  IDENTIFICATION: 'Identificación',
  REPORT: 'Informe',
  OTHER: 'Otro',
};

export const STATUS_LABELS: Record<DocumentStatus, string> = {
  UPLOADED: 'Subido',
  PROCESSING: 'Procesando',
  COMPLETED: 'Completado',
  FAILED: 'Fallido',
};

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: 'ADMIN' | 'USER';
  organizationId: string;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface ProcessingAttempt {
  id: string;
  attempt: number;
  status: ProcessingStatus;
  requestedType: RequestedDocumentType;
  queuedAt: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
  errorCode?: string;
  errorMessage?: string;
}

export interface DocumentSummary {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  requestedType: RequestedDocumentType;
  detectedType?: DocumentType;
  status: DocumentStatus;
  processingTimeMs?: number;
  ownerId: string;
  createdAt: string;
}

export interface DocumentDetail extends DocumentSummary {
  checksumSha256: string;
  latestProcessing?: ProcessingAttempt;
  actions: { process: boolean; retry: boolean; delete: boolean };
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DocumentStats {
  total: number;
  uploaded: number;
  processing: number;
  completed: number;
  failed: number;
  averageProcessingTimeMs: number | null;
  byType: Partial<Record<DocumentType, number>>;
}

export interface ProcessingStatusResponse {
  documentId: string;
  documentStatus: DocumentStatus;
  current?: ProcessingAttempt;
  stage?: string;
  attempts: ProcessingAttempt[];
}

export interface DocumentResult {
  documentId: string;
  processingId: string;
  documentType: DocumentType;
  confidence: number;
  language: string | null;
  summary: string;
  entities: Record<string, unknown>;
  text: string;
  processingTimeMs: number;
  metadata: Record<string, unknown>;
  createdAt: string;
}

/** Error RFC 7807 que devuelve la API. */
export interface Problem {
  status: number;
  title: string;
  detail: string;
  code: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}
