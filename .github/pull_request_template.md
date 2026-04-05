## Summary

<!-- What does this PR do and why? -->

## Testing perspectives

<!-- Delete lines that don't apply. Check lines you've verified. -->

- [ ] **Round-trip**: If this PR has a producer/consumer pair, there's a test connecting them
- [ ] **Contract**: New config fields are traced from YAML through to the target system
- [ ] **Sentinel**: Default-value comparisons use named constants, not magic numbers
- [ ] **Template**: Generated files can be parsed/used without manual fixup
- [ ] **Boundary**: Cross-module composition is tested, not just individual functions
- [ ] **Secrets**: Sensitive values are fully masked in all display/log paths
- [ ] **Platform**: Tests pass on both Unix and Windows (CI matrix covers this)

## Test plan

<!-- How did you verify this works? -->
