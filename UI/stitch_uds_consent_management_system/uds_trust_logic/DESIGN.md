---
name: UDS Trust Logic
colors:
  surface: '#fcf8ff'
  surface-dim: '#dcd8e3'
  surface-bright: '#fcf8ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f6f2fc'
  surface-container: '#f0ecf6'
  surface-container-high: '#eae6f1'
  surface-container-highest: '#e4e1eb'
  on-surface: '#1b1b22'
  on-surface-variant: '#464553'
  inverse-surface: '#303037'
  inverse-on-surface: '#f3eff9'
  outline: '#777584'
  outline-variant: '#c8c4d5'
  surface-tint: '#544fc0'
  primary: '#1f108e'
  on-primary: '#ffffff'
  primary-container: '#3730a3'
  on-primary-container: '#a9a7ff'
  inverse-primary: '#c3c0ff'
  secondary: '#545f73'
  on-secondary: '#ffffff'
  secondary-container: '#d5e0f8'
  on-secondary-container: '#586377'
  tertiary: '#511c00'
  on-tertiary: '#ffffff'
  tertiary-container: '#752c00'
  on-tertiary-container: '#fe9562'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e2dfff'
  primary-fixed-dim: '#c3c0ff'
  on-primary-fixed: '#0f0069'
  on-primary-fixed-variant: '#3b35a7'
  secondary-fixed: '#d8e3fb'
  secondary-fixed-dim: '#bcc7de'
  on-secondary-fixed: '#111c2d'
  on-secondary-fixed-variant: '#3c475a'
  tertiary-fixed: '#ffdbcc'
  tertiary-fixed-dim: '#ffb694'
  on-tertiary-fixed: '#351000'
  on-tertiary-fixed-variant: '#7a3003'
  background: '#fcf8ff'
  on-background: '#1b1b22'
  surface-variant: '#e4e1eb'
typography:
  page-title:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 38px
    letterSpacing: -0.02em
  section-header:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  label-caps:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
  data-mono:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '450'
    lineHeight: 16px
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 4px
  container-margin: 32px
  gutter: 16px
  table-row-height: 44px
  sidebar-width: 260px
---

## Brand & Style
The design system is engineered for high-stakes regulatory environments where clarity and authority are paramount. The visual language balances **Modern Corporate** efficiency with **Minimalist** precision to reduce cognitive load in data-dense scenarios.

The emotional response should be one of "Absolute Compliance." By utilizing wide horizontal canvases, structured information density, and a restricted aesthetic, the UI communicates that the data is organized, secure, and legally defensible. Elements are grounded and stable, avoiding trendy blurs or organic shapes in favor of architectural rigidity and clear functional boundaries.

## Colors
This design system uses a logic-driven palette designed for long-duration focused work.

- **Primary (Indigo-800):** Reserved for primary actions, active navigation states, and branding accents.
- **Secondary (Slate-800):** Used for structural navigation and text hierarchy to provide a sense of "The Vault."
- **Background (Slate-50):** A cool, neutral base that prevents eye strain during audit reviews.
- **Semantic States:** These colors are used exclusively for status indicators (Verified, Warning, Breach). They should never be used decoratively.
- **Neutral Grays:** Used for borders and auxiliary text to maintain a low-contrast environment that highlights critical data points.

## Typography
Legibility at scale is the priority. **Inter** provides a neutral, systematic rhythm for the vast majority of the UI.

- **Page Titles:** Bold and high-contrast (Slate-900) to anchor the user's location within deep nested hierarchies.
- **Data Mono:** Use **JetBrains Mono** for all UUIDs, cryptographic hashes, and policy version numbers. This ensures that characters like '0' and 'O' are never confused during manual audits.
- **Label Caps:** Used for table headers and metadata labels to distinguish them from actionable content.
- **Body-sm:** The default size for high-density data tables to maximize vertical information density without sacrificing readability.

## Layout & Spacing
The layout follows a **Fixed-Fluid Hybrid** model. The Global Navigation Sidebar is fixed, while the primary content area uses a 12-column fluid grid designed to accommodate ultra-wide monitors used in professional workstations.

- **Vertical Rhythm:** Built on a 4px baseline. Components should use 8px, 16px, or 24px increments for internal padding.
- **Grid Strategy:** For Audit Logs and Privacy Notices, use a 12-column layout. Main content spans 8-10 columns, with the remainder used for "Fiduciary Scope" side-panels or context-sensitive help.
- **Responsive Behavior:** 
  - **Desktop (1440px+):** Full 12-column display.
  - **Tablet (768px - 1439px):** Sidebar collapses to icons; margins reduce to 16px.
  - **Mobile:** Not a primary use case, but should stack to a single column for emergency "Breach Notification" viewing.

## Elevation & Depth
To maintain a professional, "flat-file" aesthetic, elevation is communicated through **Tonal Layering** and **Minimal Strokes** rather than heavy shadows.

- **Surface 0 (Background):** #F8FAFC. The lowest level.
- **Surface 1 (Cards/Tables):** Pure White (#FFFFFF) with a 1px solid stroke (#E2E8F0).
- **Surface 2 (Modals/Popovers):** Pure White with a subtle, tight shadow (0 4px 6px -1px rgb(0 0 0 / 0.1)).
- **Depth Contrast:** Use the Primary Indigo color as a thin (2px) top-border on active cards or selected entities to signal "Focus."

## Shapes
This design system utilizes **Soft** roundedness (4px) to bridge the gap between technical rigidity and modern accessibility.

- **Inputs & Buttons:** 4px radius. This reinforces the "Block" nature of the data.
- **Status Badges:** 2px radius or sharp edges to distinguish them from actionable buttons.
- **Selection Indicators:** Use vertical bars or full-height fills in tables rather than large rounded corners to maintain the columnar grid.

## Components

### High-Density Tables
- **Header:** Sticky positioning, Slate-50 background, 1px bottom border.
- **Rows:** 44px height. Use zebra striping (Slate-25) only on tables exceeding 20 rows. 
- **Filtering:** Inline "Filter Chips" that appear above the table as soon as a parameter is selected.

### Append-Only Timelines
- A vertical line (#E2E8F0) connects circular status nodes.
- Each entry must display a `data-mono` timestamp and the ID of the "Fiduciary" who authorized the change.

### Fiduciary Scope Indicators
- A persistent UI element, typically in the top-right or fixed-bottom, displaying the current legal entity (e.g., "Matrix Ltd.") and the active "Jurisdictional Context" (e.g., "EU - GDPR"). Use a high-contrast label to ensure users never modify data in the wrong legal scope.

### Multi-Entity Sidebar
- A tiered navigation system. Top level: Entity Switcher (Dropdown with Logo). Bottom level: Functional areas (Consent, Audit, Data Mapping, Notices).

### Language Switcher
- For Notice Management, use a "Matrix View" or tabbed list showing the 22-language translation status (Complete/Pending/Needs Update) for every active legal notice.