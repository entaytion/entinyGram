// entiny patches are grouped by topic under patches/entiny/<topic>/. Every entiny__ patch must be listed in patch-topics.json.
import topicsJson from './patch-topics.json'

export const ENTINY_TOPICS = [
  'accounts',
  'ai',
  'build',
  'calls',
  'chat',
  'feed',
  'media',
  'misc',
  'notifications',
  'premium',
  'privacy',
  'proxy',
  'restrictions',
  'settings',
  'tabs',
  'translator',
  'ui',
] as const

export type EntinyTopic = typeof ENTINY_TOPICS[number]

const topics = topicsJson as Record<string, string>

export function topicOf(patchName: string): EntinyTopic {
  const name = patchName.startsWith('entiny__') ? patchName.slice('entiny__'.length) : patchName
  const topic = topics[name]
  if (!topic) {
    throw new Error(`No topic for ${patchName}; add it to scripts/patch-topics.json`)
  }
  if (!(ENTINY_TOPICS as readonly string[]).includes(topic)) {
    throw new Error(`Unknown topic "${topic}" for ${patchName} in scripts/patch-topics.json`)
  }
  return topic as EntinyTopic
}
