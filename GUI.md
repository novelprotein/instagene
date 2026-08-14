# InstaGene GUI System

## Overview

A complete Swing-based graphical user interface for the InstaGene sequence editor, providing DNA/RNA sequence editing, restriction enzyme mapping, and plasmid visualization.

## Architecture

### Core Components

**Main Window** (`InstaGeneWindow.kt`)
- Central application window with menu bar, toolbar, and split pane layout
- Integrates sequence editor and the tool tabs (Enzyme, Analysis, Features, Primers, Sequence, Map, Info)
- Manages window state and file handling

**Document Model** (`SeqDocument.kt`)
- Encapsulates active sequence and editing state
- Manages undo/redo history (up to 100 actions)
- Tracks selection, caret position, mapped enzymes, and cut sites
- Observer pattern for notifying views of changes

**Sequence Editor** (`SequenceView.kt`)
- Core editing component with custom 2D rendering
- Features:
  - Per-base coloring (ACGTU nucleotides)
  - Complement strand display
  - Translation track with frame selection
  - Restriction enzyme cut site markers
  - Feature annotations with lane-based overlap avoidance
  - Full keyboard/mouse support
  - Scrollable viewport with size-adaptive layout

**Restriction Digestion Panel** (`DigestPanel.kt`) — the **Enzyme** tab
- Interactive enzyme selection and filtering
- Real-time digest fragment calculation
- Table display of fragments with size calculations
- Fragment extraction callbacks

**Features Panel** (`FeaturesPanel.kt`) — the **Features** tab
- Table of annotated features with strand and coordinates
- Click a feature to jump to it in the editor
- Add a feature from the current selection (undoable), delete selected features

**Primers Panel** (`PrimersPanel.kt`) — the **Primers** tab
- PCR primer design for the selected amplicon (From/To follow the editor selection)
- Adjustable target melting temperature
- Forward/reverse pair table with Tm and GC%, copy as FASTA
- Advanced candidate search with length, Tm, GC, and self-complementarity filters

**Analysis Panel** (`AnalysisPanel.kt`) — the **Analysis** tab
- Persistent sub-tabs for advanced search, alignment, enzyme analysis, assembly, virtual gels, calculators, NCBI/BLAST, and chromatograms
- Assembly products can be previewed, opened as new documents, or saved
- Network actions are explicit and run off the Swing event thread

**Sequence Panel** — the **Sequence** tab
- The main sequence editor lives on this tab, so it is visible only while the tab is selected
- Read-only text views of the molecule are available through the editor's complement/translation tracks

**Info Panel** (`InfoPanel.kt`) — the **Info** tab
- Editable name and description (undoable)
- Kind, topology, length, file, GC, Tm, molecular weight and feature count

**Plasmid Map Panel** (`PlasmidMapPanel.kt`) — the **Map** tab
- Circular restriction map visualization
- Feature and cut site rendering
- Selection callbacks for coordinated highlighting

### Menu System

**File Menu** (`FileMenu.kt`)
- New sequence
- Open sequence files (FASTA, GenBank, etc.)
- Save and Save As with format detection
- Exit with unsaved changes confirmation

**Edit Menu** (`EditMenu.kt`)
- Undo/Redo with history stack
- Select All, Copy, Paste, Cut, Delete
- Find sequence pattern with navigation

**View Menu** (`ViewMenu.kt`)
- Toggle complement strand display
- Toggle translation display
- Zoom in/out (8-28pt)
- Reset zoom to default

**Tools Menu** (`ToolsMenu.kt`)
- Add enzyme by name with autocomplete
- Browse common enzymes by letter
- Clear all mapped enzymes
- Virtual gel simulation with completion percentage
- Sequence alignment against a selected query file
- Sequence identity generation and verification
- Molecular dilution calculator
- About dialog

### Toolbar & Status Bar

**ToolbarActions** (`ToolbarActions.kt`)
- Quick-access buttons for file operations
- Undo/Redo buttons
- Edit functions (Select All, Copy, Paste)
- Font size spinner (8-28pt)

**StatusBar** (`StatusBar.kt`)
- Real-time sequence statistics (length, type, topology)
- GC content percentage
- Selection information (if selected)
- Cut site count

## Usage

### Running the GUI

```bash
# Launches the desktop GUI (or use `./gradlew run` from the project root)
./gradlew :app-gui:runGui

# Or with a file to open
./gradlew :app-gui:runGui --args="/path/to/sequence.fasta"
```

### CLI Mode

The command-line platform is a separate app:
```bash
./gradlew :app-cli:runCli --args="digest --enzymes BamHI,EcoRI sequence.fasta"
```

### Keyboard Shortcuts

| Action     | Shortcut     |
|------------|--------------|
| New        | Ctrl+N       |
| Open       | Ctrl+O       |
| Save       | Ctrl+S       |
| Save As    | Ctrl+Shift+S |
| Undo       | Ctrl+Z       |
| Redo       | Ctrl+Y       |
| Select All | Ctrl+A       |
| Copy       | Ctrl+C       |
| Paste      | Ctrl+V       |
| Cut        | Ctrl+X       |
| Find       | Ctrl+F       |
| Zoom In    | Ctrl++       |
| Zoom Out   | Ctrl+-       |
| Reset Zoom | Ctrl+0       |

### Editing Features

**Text Input**
- Type nucleotide bases (ACGTURYSWKMBDHVN)
- Bases automatically uppercase
- Invalid characters ignored
- Supports full alphanumeric text pasting (filtered)

**Selection**
- Click-and-drag to select region
- Shift+Click to extend selection
- Arrow keys with Shift for selection
- Home/End for line selection
- Ctrl+A for select all

**Navigation**
- Arrow keys move caret
- Page Up/Down for 10-line jumps
- Ctrl+F to find and navigate to pattern
- Double-click features to select
- Click features to navigate

**Display Options**
- Toggle complement strand (pairs with selection)
- Toggle translation with frame selector
- Adjust font size for readability
- Grid lines every 10 bases

## File Format Support

**Reading**: FASTA, GenBank, and other common bioinformatics formats via SeqIO

**Writing**: FASTA, GenBank, and GFF3 (auto-detection based on extension)

**File Extensions Recognized**:
- `.fasta`, `.fa`, `.fna`, `.fas`, `.seq`, `.txt`
- `.gb`, `.gbk`, `.genbank`, `.ape`
- `.gff`, `.gff3`

## Data Flow

```
User Input (Menu/Keyboard/Mouse)
    ↓
InstaGeneWindow / Menu Handlers
    ↓
SeqDocument.mutate() / setter methods
    ↓
Undo/Redo Stack Management
    ↓
Listener Notification
    ↓
SequenceView / DigestPanel / PlasmidMapPanel
    ↓
UI Rendering
```

## Implementation Details

### Coordinate System

- Sequence indices are 0-based internally
- Display (gutter) shows 1-based positions
- SequenceView maintains layout metrics dynamically
- Bounds checking ensures safe index access

### Performance

- Clipping for off-screen content (SequenceView only renders visible rows)
- Lazy feature lane assignment
- Efficient pattern searching
- Configurable history limit (default 100 entries)

### Extensibility

The architecture supports future enhancements by adding sub-panels to the shared Analysis workspace, file handlers to SeqIO, or display tracks to SequenceView. Analysis tools are also available through the Tools > Analysis Workspace shortcuts.

## Building from Source

```bash
# Full build
./gradlew build

# Run a single front-end (defaults to GUI; -Pplatform=cli|gui|web)
./gradlew run

# Run the desktop GUI directly
./gradlew :app-gui:runGui

# Run the CLI directly
./gradlew :app-cli:runCli

# Run the web front-end directly
./gradlew :app-web:runWeb

# Tests
./gradlew test

# Clean
./gradlew clean
```

## Project Structure

Each platform front-end is its own standalone application; they share only the
engine, and the web server opens only when the web app is run explicitly.

```
engine/
└── src/main/kotlin/org/instagene/core/     # Core sequence engine (no UI deps)

app-cli/
├── build.gradle.kts
└── src/main/kotlin/org/instagene/app/cli/
    ├── CliMain.kt              # Command-line entry point
    ├── Cli.kt
    └── Args.kt

app-gui/
├── build.gradle.kts
└── src/main/kotlin/org/instagene/app/gui/
    ├── GuiMain.kt              # Desktop entry point
    ├── InstaGeneWindow.kt
    ├── SequenceView.kt
    ├── DigestPanel.kt
    ├── PlasmidMapPanel.kt
    ├── FeaturesPanel.kt
    ├── PrimersPanel.kt
    ├── InfoPanel.kt
    ├── SeqDocument.kt
    ├── Palette.kt
    ├── StatusBar.kt
    ├── FileMenu.kt
    ├── EditMenu.kt
    ├── ViewMenu.kt
    ├── ToolsMenu.kt
    └── ToolbarActions.kt

app-web/
├── build.gradle.kts
├── src/main/kotlin/org/instagene/app/web/
│   ├── WebMain.kt              # Web entry point
│   └── WebServer.kt            # HTTP server + JSON API over the engine
└── src/main/resources/web/
    ├── index.html              # HTML5 front-end
    ├── style.css
    └── app.js
```

## Web Front-End

The HTML5 front-end (`app-web`) is an embedded HTTP server built on the JDK
`HttpServer` — no extra dependencies. It serves the browser UI and exposes the
engine over a small JSON API (`/api/samples`, `/api/open`, `/api/op`).

```bash
# Serves http://localhost:8080
./gradlew :app-web:runWeb

# Or via the root picker
./gradlew run -Pplatform=web

# Or with an explicit port
./gradlew :app-web:runWeb --args="--port 9000"
```

The web server opens only when the web platform is run explicitly, and at most
once per process.

The UI can open bundled samples, a local FASTA/GenBank file, or pasted text,
then transform (revcomp, complement, transcribe, translate, …) and analyze
(info, GC, Tm, ORFs, digest, find) the sequence. The whole stack runs on the
engine module — the same code the CLI and desktop GUI use.

## Parity Workflows

- [x] Advanced IUPAC, mismatch, amino-acid, and 3′-constrained search
- [x] GFF3 annotation import/export
- [x] Virtual digest gel calculations
- [x] Sequence alignment engine and desktop viewer
- [x] Sequence identity generation
- [x] Molecular dilution and master-mix calculations
- [x] Editable SVG/PNG plasmid map export
- [ ] NCBI and chromatogram integrations
- [ ] Full Golden Gate/recombination designer dialogs
- [x] Theme support (dark mode)
- [x] Tabbed multi-file editing
- [x] Hiding elements when not needed
