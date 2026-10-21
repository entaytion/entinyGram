import type { DevMemorySample } from '../utils/device.js'
import { stripVTControlCharacters } from 'node:util'
import { sleep } from '@fuman/utils'
import { configArgs, defineCommand, deviceArgs } from '../utils/args.js'
import { Device } from '../utils/device.js'
import { CliError, color } from '../utils/log.js'
import { loadProject } from '../utils/project.js'

function formatMb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)} mb`
}

function formatDelta(now: number, before: number | undefined, format: (value: number) => string): string {
  if (before === undefined || now === before) return ''
  const delta = now - before
  const text = `${delta > 0 ? '+' : '-'}${format(Math.abs(delta))}`
  return ` ${delta > 0 ? color.red(text) : color.green(text)}`
}

const HEADER = ['plugin', 'heap', 'native', 'objects', 'arrays', 'functions', 'strings', 'string data', 'shapes', 'atoms']

function formatRow({ plugin, usage }: DevMemorySample, previous: DevMemorySample | undefined): string[] {
  const before = previous?.usage
  return [
    color.bold(plugin.name),
    `${formatMb(usage.heapBytes)} / ${formatMb(usage.heapLimitBytes)}${formatDelta(usage.heapBytes, before?.heapBytes, value => `${(value / 1024).toFixed(1)} kb`)}`,
    `${formatMb(usage.nativeBytes)} / ${formatMb(usage.nativeLimitBytes)}`,
    `${usage.objects}${formatDelta(usage.objects, before?.objects, String)}`,
    String(usage.arrays),
    String(usage.functions),
    String(usage.strings),
    formatMb(usage.stringBytes),
    String(usage.shapes),
    String(usage.atoms),
  ]
}

/** the plugin column is left-aligned, the figures right-aligned */
function printTable(rows: string[][]) {
  const widths = HEADER.map((title, i) => Math.max(title.length, ...rows.map(row => stripVTControlCharacters(row[i]).length)))
  const pad = (cell: string, i: number) => {
    const gap = ' '.repeat(widths[i] - stripVTControlCharacters(cell).length)
    return i === 0 ? cell + gap : gap + cell
  }
  console.log(color.gray(HEADER.map(pad).join('  ')))
  for (const row of rows) console.log(row.map(pad).join('  '))
}

export const memoryCmd = defineCommand({
  meta: { name: 'memory', description: 'print how much memory are running plugins using' },
  args: {
    ...configArgs,
    ...deviceArgs,
    name: {
      type: 'positional',
      required: false,
      description: 'the plugin to read (default: every running plugin)',
    },
    watch: {
      type: 'string',
      alias: 'w',
      description: 'sample again every this many seconds',
    },
  },
  run: async ({ args }) => {
    const seconds = args.watch === undefined ? null : Number(args.watch)
    if (seconds !== null && !(seconds > 0)) throw new CliError(`--watch must be a positive number of seconds, got '${args.watch}'`)
    const project = args.name === undefined ? null : await loadProject({ config: args.config, _: [args.name] })
    const pluginFile = project?.plugins[0]?.outFile ?? null

    const device = new Device(args)
    await device.requireRunning()
    let previous = new Map<string, DevMemorySample>()
    while (true) {
      const samples = await device.memory(pluginFile)
      if (seconds !== null) console.log(color.gray(new Date().toLocaleTimeString()))
      if (samples.length === 0) console.log(color.gray('nothing running'))
      else printTable(samples.map(sample => formatRow(sample, previous.get(sample.plugin.id))))
      if (seconds === null) return
      previous = new Map(samples.map(sample => [sample.plugin.id, sample]))
      await sleep(seconds * 1000)
    }
  },
})
