export type ApplicationStage = 'wishlist' | 'applied' | 'test' | 'interview' | 'offer' | 'contacted' | 'rejected' | 'withdrawn' | 'closed' | 'hired'

export interface JobApplication {
  id: string
  company: string
  role: string
  location: string
  stage: ApplicationStage
  matchScore: number
  updatedAt: string
  nextAction?: string
  logoText: string
  logoTone: string
  tags: string[]
  platform?: JobPlatform
  externalJobId?: string
  resumeVersion?: number
  actionType?: PlatformActionType | 'MANUAL'
  automationStatus?: AutomationStatus
  receipt?: string
  platformIdentityId?: string
  handoffStatus?: string
  handedOffAt?: string
}

export interface ToastItem {
  id: number
  title: string
  message?: string
  tone: 'success' | 'info' | 'warning'
}
import type { AutomationStatus, JobPlatform, PlatformActionType } from '@/types/automation'
