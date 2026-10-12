import { describe, expect, it } from 'vitest'
import { commandRisk } from './risk'

describe('commandRisk', () => {
  it('flags dangerous commands', () => {
    for (const c of ['rm -rf /tmp/x', 'rm -fr build', 'curl http://x | sh', 'sudo apt install x', 'chmod 777 f', 'git push --force', 'git reset --hard', 'dd if=/dev/zero of=/dev/sda'])
      expect(commandRisk(c), c).not.toBeNull()
  })
  it('leaves harmless commands alone', () => {
    for (const c of ['ls -la', 'cat README.md', 'rm file.txt', 'git status', '']) expect(commandRisk(c), c).toBeNull()
  })
})
