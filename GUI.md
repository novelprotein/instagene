# InstaGene GUI System

## Overview

A complete Swing-based graphical user interface for the InstaGene sequence editor, providing DNA/RNA sequence editing, restriction enzyme mapping, and plasmid visualization.

## Architecture

### Core Components

**Main Window** (`InstaGeneWindow.kt`)
- Central application window with menu bar, toolbar, and split pane layout
- Integrates sequence editor, digestion panel, and plasmid map in tabbed interface
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

**Restriction Digestion Panel** (`DigestPanel.kt`)
- Interactive enzyme selection and filtering
- Real-time digest fragment calculation
- Table display of fragments with size calculations
- Fragment extraction callbacks

**Plasmid Map Panel** (`PlasmidMapPanel.kt`)
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
# No arguments or 'gui' launches the GUI
./gradlew run

# Or with a file to open
./gradlew run gui /path/to/sequence.fasta
```

### CLI Mode

All other arguments are passed to the CLI:
```bash
./gradlew run digest -e BamHI -e EcoRI sequence.fasta
```

### Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| New | Ctrl+N |
| Open | Ctrl+O |
| Save | Ctrl+S |
| Save As | Ctrl+Shift+S |
| Undo | Ctrl+Z |
| Redo | Ctrl+Y |
| Select All | Ctrl+A |
| Copy | Ctrl+C |
| Paste | Ctrl+V |
| Cut | Ctrl+X |
| Find | Ctrl+F |
| Zoom In | Ctrl++ |
| Zoom Out | Ctrl+- |
| Reset Zoom | Ctrl+0 |

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

**Writing**: FASTA format (auto-detection based on extension)

**File Extensions Recognized**:
- `.fasta`, `.fa`, `.fna`, `.fas`, `.seq`, `.txt`
- `.gb`, `.gbk`, `.genbank`, `.ape`

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

The architecture supports future enhancements:

1. **Additional Panels**: Implement similar to DigestPanel/PlasmidMapPanel
2. **File Formats**: Extend SeqIO with new format handlers
3. **Display Modes**: Add new tracks or visualization modes to SequenceView
4. **Analysis Tools**: Add to Tools menu with analysis workflow

## Building from Source

```bash
# Full build
./gradlew build

# Run application
./gradlew run

# Tests
./gradlew test

# Clean
./gradlew clean
```

## Project Structure

```
app/
├── build.gradle.kts
└── src/main/kotlin/org/instagene/app/
    ├── Main.kt                 # Entry point
    ├── cli/                    # CLI module
    └── gui/                    # GUI components
        ├── InstaGeneWindow.kt
        ├── SequenceView.kt
        ├── DigestPanel.kt
        ├── PlasmidMapPanel.kt
        ├── SeqDocument.kt
        ├── Palette.kt
        ├── StatusBar.kt
        ├── FileMenu.kt
        ├── EditMenu.kt
        ├── ViewMenu.kt
        ├── ToolsMenu.kt
        └── ToolbarActions.kt
```

## Future Enhancements

- [ ] Batch enzyme editing
- [ ] Custom enzyme definitions
- [ ] Sequence alignment visualization
- [ ] PCR primer design
- [ ] Multi-sequence comparison
- [ ] Import from NCBI
- [ ] Export to various formats (GFF, JSON, etc.)
- [ ] Theme support (dark mode)
- [ ] Tabbed multi-file editing
- [ ] Hiding elements when not needed
- 