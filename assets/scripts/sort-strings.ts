import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const RES = join(import.meta.dir, '..', '..', 'src', 'res')
const check = process.argv.includes('--check')

type Entry = { key: string; lines: string[] }

function sortFile(path: string): { changed: boolean; text: string } {
    const raw = readFileSync(path, 'utf8')
    const eol = raw.includes('\r\n') ? '\r\n' : '\n'
    const lines = raw.replace(/\r\n/g, '\n').split('\n')
    const open = lines.findIndex((l) => l.trim() === '<resources>')
    const close = lines.lastIndexOf('</resources>')
    if (open < 0 || close < 0) throw new Error(`${path}: no <resources> block`)

    const entries: Entry[] = []
    let pending: string[] = []
    let cur: Entry | null = null
    for (const line of lines.slice(open + 1, close)) {
        if (cur) {
            cur.lines.push(line)
            if (line.includes('</string>')) cur = null
            continue
        }
        const m = line.match(/^\s*<string name="([^"]+)"/)
        if (m) {
            cur = { key: m[1], lines: [...pending, line] }
            entries.push(cur)
            pending = []
            if (line.includes('</string>')) cur = null
        } else if (line.trim() === '') {
            continue
        } else if (/^\s*<!--.*-->\s*$/.test(line)) {
            pending.push(line)
        } else {
            throw new Error(`${path}: unsupported line, sort by hand first: ${line.trim().slice(0, 80)}`)
        }
    }
    if (pending.length) throw new Error(`${path}: trailing comment without a string`)

    entries.sort((a, b) => {
        const x = a.key.toLowerCase()
        const y = b.key.toLowerCase()
        return x < y ? -1 : x > y ? 1 : a.key < b.key ? -1 : a.key > b.key ? 1 : 0
    })

    const out = [...lines.slice(0, open + 1), ...entries.flatMap((e) => e.lines), ...lines.slice(close)]
    const text = out.join('\n').replace(/\n/g, eol)
    return { changed: text !== raw, text }
}

const dirs = readdirSync(RES).filter((d) => d.startsWith('values'))
let unsorted = 0
for (const dir of dirs) {
    const path = join(RES, dir, 'strings_inu.xml')
    if (!existsSync(path)) continue
    const { changed, text } = sortFile(path)
    if (!changed) continue
    unsorted++
    if (check) console.log(`unsorted  ${dir}/strings_inu.xml`)
    else {
        writeFileSync(path, text, 'utf8')
        console.log(`sorted    ${dir}/strings_inu.xml`)
    }
}

if (check) {
    if (unsorted) {
        console.log(`\n${unsorted} file(s) not sorted, run: bun run tsx assets/scripts/sort-strings.ts`)
        process.exit(1)
    }
    console.log('ok all strings_inu.xml files are sorted')
} else if (!unsorted) {
    console.log('ok already sorted')
}
