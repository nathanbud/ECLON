import { getJson, send } from './http'

// Settings > Backup (administrators): the backups kept on the server's disk, and taking a new one.

export interface BackupFile { name: string; bytes: number }
export interface BackupEntry { name: string; takenAt: string; bytes: number; complete: boolean; files: BackupFile[] }
export interface BackupStatus {
  /** The database the backup is taken from: postgres or mysql. */
  engine: string
  /** The folder on the server where the backups are kept. */
  folder: string
  scriptFound: boolean
  running: boolean
  backups: BackupEntry[]
}
export interface BackupRun { ok: boolean; message: string; backup: BackupEntry | null }

export const getBackups = () => getJson<BackupStatus>('/api/admin/backups')
export const runBackup = () => send<BackupRun>('POST', '/api/admin/backups')
