# Native installer verification

InstaGene builds native packages on their target operating systems. Structural
package checks run in CI, but a release owner still needs to perform the
platform-specific visual and trust checks below before describing a release as
fully signed or platform-verified.

## Current release boundary

The repository validates unsigned installer structure, launch metadata, native
CLI smoke behavior, and declared file associations in native CI jobs. It does
not currently have protected Windows signing or Apple notarization credentials.
Therefore unsigned artifacts must be labeled as unsigned; do not claim
SmartScreen reputation, notarization, or signing assessment until the relevant
credential-gated checks have run.

## Windows release-owner checklist

1. Install the MSI on a clean Windows machine or VM.
2. Confirm the application name, icon, version metadata, Start Menu entry,
   uninstall entry, and per-user install behavior.
3. Use **Open with** and a shell launch for a representative FASTA, GenBank,
   GFF3, EMBL, Swiss-Prot, and alignment file. Confirm the file reaches the
   unified open flow.
4. Inspect the file chooser at normal and high DPI scaling and confirm that
   text, filters, and progress/cancel controls remain usable.
5. Capture the installer, installed application, association, and high-DPI
   states with the release version visible. Store those captures with the
   release evidence before adding them to public documentation.
6. When protected signing material is available, sign the MSI/executable and
   verify its Authenticode signature before upload.

## macOS release-owner checklist

1. Mount the DMG on a clean supported macOS machine and drag `InstaGene.app`
   to Applications.
2. Confirm the app icon, bundle identifier, version, launch behavior, and
   document-type association metadata.
3. Open representative supported sequence and alignment files from Finder and
   inspect the file chooser and plasmid map on a Retina display.
4. Capture the DMG, Applications installation, Finder open flow, and Retina
   map/workspace states with the release version visible. Store those captures
   with the release evidence before publishing them.
5. When Apple Developer credentials are available, sign the app, notarize and
   staple the DMG, then run `spctl --assess` on the final artifact.

## Screenshot status

The documentation currently contains real desktop-workspace screenshots under
`docs/screenshots/`. Native installer screenshots are intentionally not
fabricated from mockups: they remain a release-owner capture requirement until
the Windows and macOS checks above are run on their native systems.

## Linux and portable checks

Install a DEB or RPM on its intended distribution, or run the app-image
launcher. Confirm the desktop entry, MIME metadata, launcher, and a standard
file-open path. For the portable GUI JAR, confirm Java 21+, allocate an
appropriate heap for the expected data size, and verify it makes no file
association changes.

See [Release and packaging](releasing.md) for CI and credential handling.
