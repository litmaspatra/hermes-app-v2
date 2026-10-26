import { expect, test } from 'vitest'
import { statusWord } from './statusword'

test('Hermes thinking words lose the face and get one ellipsis', () => {
  expect(statusWord('(｡•́︿•̀｡) pondering...')).toBe('Pondering…')
  expect(statusWord('ヽ(>∀<☆)ノ deliberating…')).toBe('Deliberating…')
  expect(statusWord('initializing agent')).toBe('Initializing agent…')
  expect(statusWord('  Contemplating  ')).toBe('Contemplating…')
})

test('anything that is not a short word is not sent to the island', () => {
  expect(statusWord('')).toBe('')
  expect(statusWord('(｡•́︿•̀｡)')).toBe('')
  expect(statusWord('Let me check the file and then run the tests for you')).toBe('')
  expect(statusWord('/data/data/com.termux/files/home')).toBe('')
  expect(statusWord('run_tests')).toBe('')
  expect(statusWord('a'.repeat(40))).toBe('')
})
