export interface Scenario {
  id: string
  name: string
  possibleStates: string[]
  state: string
}

export interface ScenariosResponse {
  scenarios: Scenario[]
}

export interface DelayDistribution {
  type: 'lognormal' | 'uniform' | 'fixed'
  median?: number
  sigma?: number
  lower?: number
  upper?: number
  milliseconds?: number
}

export interface GlobalSettings {
  fixedDelay?: number
  delayDistribution?: DelayDistribution
}

export interface HealthResponse {
  status: 'healthy' | 'unhealthy'
  message: string
  version: string
  uptimeInSeconds: number
  timestamp: string
}

export interface VersionResponse {
  version: string
}

export interface RecordingStatusResponse {
  status: 'NeverStarted' | 'Recording' | 'Stopped'
}

export interface GlobalSettingsResponse {
  fixedDelay?: number
  delayDistribution?: DelayDistribution | null
}
