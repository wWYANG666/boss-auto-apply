import type { Platform } from '../domain.js'
import type { JobPlatformAdapter } from './contract.js'
import { BossBrowserAdapter } from './boss-browser.js'
import { FakeAdapter } from './fake.js'
import { LiepinMcpAdapter } from './liepin-mcp.js'

export class AdapterRegistry {
  private readonly adapters: Map<Platform, JobPlatformAdapter>

  constructor() {
    const fake = new FakeAdapter()
    this.adapters = new Map<Platform, JobPlatformAdapter>([
      ['fake', fake],
      ['boss', process.env.RUNNER_MODE !== 'real' ? fake : new BossBrowserAdapter()],
      ['liepin', process.env.RUNNER_MODE !== 'real' ? fake : new LiepinMcpAdapter(process.env.LIEPIN_MCP_TOKEN, process.env.LIEPIN_MCP_ENDPOINT)],
    ])
  }

  get(platform: Platform): JobPlatformAdapter {
    const adapter = this.adapters.get(platform)
    if (!adapter) throw new Error(`No adapter registered for platform ${platform}.`)
    return adapter
  }

  entries(): Array<[Platform, JobPlatformAdapter]> {
    return [...this.adapters.entries()]
  }
}
