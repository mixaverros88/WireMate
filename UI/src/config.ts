export const WIREMOCK_ADMIN_URL = (import.meta.env.VITE_WIREMOCK_ADMIN_URL as string) || 'http://localhost:8080/__admin'

// Base URL of the WireMock service where mocks are actually served (admin URL without the /__admin suffix).
export const WIREMOCK_BASE_URL = WIREMOCK_ADMIN_URL.replace(/\/__admin\/?$/, '')

// Base URL for the WireMate Spring Boot backend API
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string) || 'http://localhost:8081/api'
