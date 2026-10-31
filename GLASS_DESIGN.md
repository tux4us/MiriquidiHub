# Glas-Design (nur Dark)

Aufbau und Benennung entsprechen der HundeWildnis-App. Die App hat ein einziges Theme
(`Theme.MiriquidiHub`, Basis `Theme.Material3.Dark.NoActionBar`). Hell/Dunkel-Umschaltung und `values-night/` sind entfernt;
`MiriquidiHubApp` setzt `MODE_NIGHT_YES`.

| Ebene | Datei | Inhalt |
|---|---|---|
| Hintergrund | `res/drawable/bg_app_gradient.xml` | dunkler Verlauf mit drei weichen Farbflächen (Grün, Türkis, Blau); als `windowBackground` gesetzt |
| Tokens | `res/values/colors_glass.xml` | Glasflächen `gl_fill*`, Ränder `gl_stroke*`, Leiste `gl_bar`, Fenster `gl_dialog`/`gl_sheet`, Rollenfarben `gl_<rolle>[_fill/_stroke/_ripple]`, Gruppenfarben `rd_groupN_accent/_stroke` |
| Basisfarben | `res/values/colors.xml` | `rd_bg`, `rd_card` (opak für Popups), Text, Akzent `rd_overlay_accent` |
| Theme | `res/values/themes.xml` | Farbrollen, globale Widget-Styles, Dialog- und Bottom-Sheet-Theme |
| Blur ab API 31 | `res/values-v31/themes.xml`, `res/drawable-v31/bg_glass_dialog.xml` | Fenster-Blur für Dialoge und Bottom Sheets |
| Styles | `res/values/styles_glass.xml` | Buttons, Karten, Eingabefelder, Chips, Leiste |
| Zustandsfarben | `res/color/gl_btn_*` | Füllung, Rand, Text je Rolle (inkl. deaktiviert) |
| Views | `GlassCardView`, `GlassButton`, `GlassPainter` | Glanzverlauf und Randverlauf (oben hell, nach unten ausgeblendet) |

## Button-Rollen
Statt Hex-Werten im Layout wird ein Style gesetzt: `Widget.App.Button[.Primary|.Success|.Warning|.Danger|.Info]`,
`Widget.App.Button.Outlined[.<Rolle>]`, `Widget.App.Button.Text[.<Rolle>]`. Ohne Style gilt neutrales Glas.
Die Rollenfarbe steckt nur im Rand sowie in Text und Icon; die Füllung ist immer neutral (`gl_fill`).

## Karten
`GlassCardView` statt `MaterialCardView`, `GlassButton` statt `MaterialButton`. `MaterialCardView` ist global auf `Widget.App.Card`
gesetzt (transparente Füllung, 1 dp Rand, keine Elevation). Karte in Karte: `style="@style/Widget.App.Card.Inner"`.
Startkacheln nutzen `rd_group1..7_stroke` (Rand, 2 dp) und `rd_group1..7_accent` (Icon).

## Regeln für neue Layouts
- Keinen eigenen `android:background` auf Wurzel-Layouts setzen; der Fensterhintergrund liefert den Verlauf.
- Keine Hex-Farben; Tokens aus `colors_glass.xml` oder `rd_*` verwenden.
- 3D-Effekt: Karten 6 dp, Buttons 4 dp Elevation (Schatten nur außerhalb der Form ab API 28), Druckanimation `animator/glass_press`, Glanz und Kantenlicht in `GlassPainter`.
- Popups, Menüs und Auswahllisten nutzen `colorSurface*` (opak), nicht `gl_fill`.
