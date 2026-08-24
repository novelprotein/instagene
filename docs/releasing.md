# Release and packaging

InstaGene packages are built on their target operating systems with GraalVM 21
and `jpackage`; installers are never cross-compiled. The release workflow
builds Linux DEB/RPM/app-image artifacts, a Windows MSI, a macOS DMG, a portable
GUI JAR, and native CLI binaries for Linux, Windows, and macOS.

## Required release checks

Before tagging a release, run the complete Gradle suite, strict documentation
validation, and the Linux distribution checks. Native GitHub Actions jobs then
install or mount each desktop package, validate launch metadata and registered
document types, and smoke-test each native CLI with `--help` and `--version`.

The Windows installer uses a fixed upgrade identity and per-user installation
scope. Its CI test verifies the installed executable, Start Menu shortcut,
registered file handlers, a real FASTA shell-open launch, and uninstall. The
macOS job verifies the DMG bundle, executable, identifier, and document-type
metadata.

## Signing and notarization

Unsigned artifacts remain usable for contributors and development builds, but
operating systems may show reputation or security warnings. Do not describe an
unsigned artifact as signed, notarized, or SmartScreen-trusted.

Production signing is intentionally credential-gated:

- Store Windows code-signing material and its password as protected repository
  secrets; sign the generated MSI and verify the Authenticode signature before
  upload.
- Store the Apple Developer certificate, certificate password, App Store
  Connect issuer/key/id, and notarization profile as protected repository
  secrets; sign the `.app`, notarize/staple the DMG, then verify with
  `spctl --assess`.
- Keep these secrets unavailable to pull requests and forks. A release without
  the required credentials must publish clearly labeled unsigned artifacts or
  fail according to the release policy.

The repository currently validates unsigned installer structure and behavior.
Enabling production signing requires the project owner to supply those
credentials and choose the unsigned-release policy.

The [native installer verification guide](platform-verification.md) separates
the CI structural checks from the remaining release-owner Windows/macOS visual,
DPI/Retina, signing, and screenshot evidence steps.
