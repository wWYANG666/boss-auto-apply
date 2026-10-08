import type { Response } from 'express'
import type { RunnerEvent } from '../domain.js'

export class EventHub {
  private readonly clients = new Set<Response>()

  addClient(response: Response): () => void {
    this.clients.add(response)
    return () => this.clients.delete(response)
  }

  publish(event: RunnerEvent): void {
    const frame = `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
    for (const client of this.clients) client.write(frame)
  }

  heartbeat(): void {
    for (const client of this.clients) client.write(': heartbeat\n\n')
  }
}
