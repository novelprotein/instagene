# InstaGene GUI System

This document is a developer-oriented overview of the current Swing desktop
front-end. The user-facing guide lives in `docs/gui.md`.

## Overview

The GUI is a Swing + FlatLaf application backed by the shared
`org.instagene.core` engine. It focuses on desktop sequence editing, file
opening/saving, restriction mapping, plasmid visualization, primer workflows,
and analysis tools that should stay responsive on researcher workstations.

## Main Components

- `InstaGeneWindow.kt` owns the main frame, menu bar, document tabs, side
  panels, file handling, drag-and-drop, and coordinated selection state.
- `SeqDocument.kt` wraps the active `Seq`, edit state, selection/caret state,
  mapped enzymes, and document listeners.
- `SequenceView.kt` renders sequence text, selection, feature annotations,
  complement/translation tracks, and restriction cut markers.
- `DigestPanel.kt`, `FeaturesPanel.kt`, `PrimersPanel.kt`, `InfoPanel.kt`, and
  `PlasmidMapPanel.kt` provide the main researcher-facing side panels.
- Analysis workflows live under the analysis/tool packages and are opened from
  the menus or workspace panels.

## Menus and Workflows

- File workflows cover new/open/save/save-as, recent files, drag-and-drop, and
  unsaved-change handling.
- Edit workflows cover undo/redo, clipboard actions, selection, deletion, and
  sequence search.
- View workflows cover complement/translation display, feature-history colors,
  zoom, panel visibility, full screen, and theme switching.
- Tools workflows cover restriction-enzyme actions, virtual gels, alignment,
  sequence identity, dilution/master-mix calculators, BLAST/NCBI helpers, and
  the analysis workspace.

## Packaging Notes

Installed desktop packages are built with the GraalVM 21 JDK and `jpackage`.
They bundle a runtime image, so users do not need to install Java separately.
The portable GUI JAR remains available for environments that prefer to provide
their own Java 21+ runtime.

- Linux packages install desktop/MIME metadata and a terminal launcher.
- macOS DMG builds create an `.app` bundle with document type metadata.
- Windows MSI builds currently install a per-user app with Start Menu shortcut;
  file associations are not wired into the native MSI yet.

## Building and Running

```bash
./gradlew build
./gradlew :app-gui:runGui
./gradlew :app-cli:runCli --args="help"
./gradlew :app-web:runWeb
```

Desktop packages are built per target OS:

```bash
./gradlew :app-gui:jpackage -PjpackageType=DEB
./gradlew :app-gui:jpackage -PjpackageType=RPM
./gradlew :app-gui:jpackageAppImageZip -PjpackageType=APP_IMAGE
./gradlew :app-gui:jpackage -PjpackageType=MSI
./gradlew :app-gui:jpackage -PjpackageType=DMG
```
