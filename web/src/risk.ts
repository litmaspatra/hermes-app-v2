// Marks dangerous commands on the approval sheet with a short plain reason. The same rules live in the Android
// shell (ApprovalPolicy.risk) for the notification and the island; keep the two lists in step.
export function commandRisk(command: string | undefined | null): string | null {
  if (!command) return null
  const c = ` ${command.toLowerCase().replace(/\s+/g, ' ')} `
  if (/\brm\b.* -[a-z]*r[a-z]*f/.test(c) || /\brm\b.* -[a-z]*f[a-z]*r/.test(c) || c.includes(' rm -r ') || c.includes('--no-preserve-root')) return 'Deletes files recursively'
  if (/\b(curl|wget)\b.*\|\s*(sudo )?(ba|z)?sh\b/.test(c)) return 'Runs a downloaded script'
  if (/ dd /.test(c) || c.includes(' dd if=')) return 'Writes raw data to a disk or file'
  if (c.includes(' mkfs') || c.includes(' format ')) return 'Formats a disk'
  if (c.includes(' chmod 777') || c.includes(' chmod -r 777')) return 'Makes files writable by everyone'
  if (c.includes(' chown -r')) return 'Changes file owners recursively'
  if (c.includes(' sudo ')) return 'Runs with administrator rights'
  if (c.includes('> /dev/') && !c.includes('> /dev/null')) return 'Writes to a device'
  if (c.includes(':(){')) return 'Fork bomb'
  if (c.includes(' shutdown') || c.includes(' reboot') || c.includes(' poweroff')) return 'Shuts down or restarts the machine'
  if (c.includes('git push') && (c.includes('--force') || c.includes(' -f '))) return 'Force-pushes and can overwrite history'
  if (c.includes('git reset --hard') || c.includes('git clean -f')) return 'Discards local changes'
  return null
}
