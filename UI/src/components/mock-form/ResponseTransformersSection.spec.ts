/**
 * Tests for ResponseTransformersSection.
 *
 * The section owns three v-modelled values (transformers list, input
 * text, parameters JSON) and one prop (faultEnabled). Behaviours:
 *   - Quick-add chips append a name to transformers.
 *   - Free-text + Enter / Add button also append, with dedup.
 *   - Removing a chip emits a shorter array.
 *   - Parameters JSON validity uses the shared validator.
 *   - Whole fieldset disables when fault sim is on.
 */

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ResponseTransformersSection from './ResponseTransformersSection.vue'

const baseProps = {
  collapsed: false,
  transformers: [] as string[],
  input: '',
  parametersJson: '',
  faultEnabled: false,
}

describe('ResponseTransformersSection', () => {
  it('renders quick-add chips for the well-known transformers', () => {
    const wrapper = mount(ResponseTransformersSection, { props: baseProps })
    expect(wrapper.text()).toContain('response-template')
    expect(wrapper.text()).toContain('webhook-template')
  })

  it('emits update:transformers when a chip is clicked', async () => {
    const wrapper = mount(ResponseTransformersSection, { props: baseProps })
    const chip = wrapper.findAll('code').find((c) => c.text() === 'response-template')!
    await chip.trigger('click')
    expect(wrapper.emitted('update:transformers')?.[0]).toEqual([['response-template']])
  })

  it('does not re-emit when a chip already in the list is clicked', async () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, transformers: ['response-template'] },
    })
    const chip = wrapper.findAll('code').find((c) => c.text() === 'response-template')!
    await chip.trigger('click')
    expect(wrapper.emitted('update:transformers')).toBeUndefined()
  })

  it('emits update:transformers + update:input when free-text Add is clicked', async () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, input: 'my-custom' },
    })
    const addButton = wrapper.findAll('button').find((b) => b.text() === 'Add')!
    await addButton.trigger('click')
    expect(wrapper.emitted('update:transformers')?.[0]).toEqual([['my-custom']])
    expect(wrapper.emitted('update:input')?.[0]).toEqual([''])
  })

  it('removes a transformer via the per-chip × button', async () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, transformers: ['response-template', 'webhook-template'] },
    })
    const removeButton = wrapper.find('button[aria-label="Remove transformer response-template"]')
    await removeButton.trigger('click')
    expect(wrapper.emitted('update:transformers')?.[0]).toEqual([['webhook-template']])
  })

  it('shows the JSON parse error for invalid parameter JSON', () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, parametersJson: '{nope}' },
    })
    expect(wrapper.text()).toMatch(/Invalid JSON/)
  })

  it('shows the "must be a JSON object" error for arrays/primitives', () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, parametersJson: '[1,2]' },
    })
    expect(wrapper.text()).toMatch(/must be a JSON object/i)
  })

  it('stays silent for a valid JSON object', () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, parametersJson: '{"a":1}' },
    })
    expect(wrapper.text()).not.toMatch(/Invalid JSON/)
    expect(wrapper.text()).not.toMatch(/must be a JSON object/i)
  })

  it('disables the entire fieldset while fault sim is on', () => {
    const wrapper = mount(ResponseTransformersSection, {
      props: { ...baseProps, faultEnabled: true },
    })
    const fieldset = wrapper.find('fieldset')
    expect(fieldset.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toMatch(/Disabled while Fault Simulation/)
  })

  it('shows the empty-state copy when no transformers are configured', () => {
    const wrapper = mount(ResponseTransformersSection, { props: baseProps })
    expect(wrapper.text()).toContain('No transformers')
  })
})
