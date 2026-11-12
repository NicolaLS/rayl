import Shared
import SwiftUI

@MainActor
private final class NativePaymentHubModel: ObservableObject {
    @Published private(set) var snapshot: NativePaymentHubSnapshot?
    let controller: NativePaymentHubController
    private var cancel: (() -> Void)?

    init(controller: NativePaymentHubController) {
        self.controller = controller
        cancel = controller.observe { [weak self] value in
            self?.snapshot = value
        }
    }

    deinit { cancel?() }
}

private enum HubRoute: Hashable {
    case variants
    case configure
}

/// A tapped Sats widget, with what its converter needs.
private struct HubConverterTarget: Identifiable {
    let id: String
    let info: NativeHubInfo
}

private extension Color {
    /// A colour that follows the appearance the app shows, which may differ from the system's.
    init(day: UIColor, night: UIColor) {
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? night : day })
    }
}

/// The colours of a tile's face. Both appearances share one family: at night a navy face that
/// brightens towards the top, by day a white one that warms into lilac at the top, each written
/// in the other's colour.
private enum HubPalette {
    private static let navyInk = UIColor(red: 0.11, green: 0.11, blue: 0.23, alpha: 1)

    static let faceTop = Color(day: UIColor(red: 0.93, green: 0.92, blue: 0.98, alpha: 1), night: UIColor(red: 0.16, green: 0.16, blue: 0.30, alpha: 1))
    static let faceBottom = Color(day: .white, night: UIColor(red: 0.06, green: 0.08, blue: 0.13, alpha: 1))
    static let ink = Color(day: navyInk, night: .white)
    /// Behind the small round actions and placeholders that sit on a face.
    static let chip = Color(day: navyInk.withAlphaComponent(0.07), night: UIColor.white.withAlphaComponent(0.14))
    static let hairline = Color(day: navyInk.withAlphaComponent(0.08), night: .clear)
    static let shadow = Color(uiColor: navyInk.withAlphaComponent(0.12))

    /// Bitcoin's own orange marks the widgets that are about bitcoin rather than a person.
    static let bitcoin = Color(red: 0.97, green: 0.58, blue: 0.10)
    static let up = Color(day: UIColor(red: 0.12, green: 0.62, blue: 0.32, alpha: 1), night: UIColor(red: 0.37, green: 0.88, blue: 0.55, alpha: 1))
    static let down = Color(day: UIColor(red: 0.85, green: 0.28, blue: 0.24, alpha: 1), night: UIColor(red: 1.0, green: 0.54, blue: 0.50, alpha: 1))
    static let ember = Color(red: 1.0, green: 0.36, blue: 0.20)
    static let claro = Color(red: 0.85, green: 0.16, blue: 0.11)

    static func glow(_ kind: String) -> Color? {
        switch kind {
        case "sats": return bitcoin
        case "ppq": return ember
        case "claro": return claro
        default: return nil
        }
    }

    /// The mark of the service a widget belongs to. Only a service brings its own.
    static func mark(_ kind: String) -> String? {
        switch kind {
        case "ppq": return "hub_ppq"
        case "claro": return "hub_claro"
        default: return nil
        }
    }
}

private enum HubGeometry {
    static let gap: CGFloat = 12
    static let gutter: CGFloat = 16
    static let corner: CGFloat = 28

    static func span(_ unit: CGFloat, _ count: Int32) -> CGFloat {
        unit * CGFloat(count) + gap * CGFloat(max(count - 1, 0))
    }
}

/// The app owns payment behavior; the Hub presents independently configured native widgets.
struct NativePaymentHubView: View {
    @StateObject private var model: NativePaymentHubModel
    /// The widget being carried, and the one it last moved onto, so a neighbour of another size
    /// cannot swap back and forth under the finger.
    @State private var draggingId: String?
    @State private var lastTargetId: String?
    /// The widget iOS lifted for a drag that may not start; kept outside the view's state, so
    /// lifting a widget for its menu changes nothing on screen.
    @State private var liftedWidget = HubLiftedWidget()
    @State private var converting: HubConverterTarget?
    @State private var toppingUp: HubPpqTarget?
    @Environment(\.scenePhase) private var scenePhase
    init(controller: NativePaymentHubController) {
        _model = StateObject(wrappedValue: NativePaymentHubModel(controller: controller))
    }

    var body: some View {
        Group {
            if let state = model.snapshot {
                canvas(state)
            } else {
                ProgressView()
            }
        }
        .sheet(isPresented: editorPresented) {
            HubEditorSheet(model: model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(model.snapshot?.busy == true)
        }
        .sheet(item: $converting) { target in
            if let state = model.snapshot {
                HubConverterSheet(target: target, text: state.text, controller: model.controller)
            }
        }
        .sheet(item: $toppingUp) { target in
            HubPpqTopUpSheet(model: model, widgetId: target.id)
        }
        .sheet(isPresented: claroPresented) {
            HubClaroSheet(model: model)
        }
        .task(id: scenePhase) {
            // Prices and balances are fetched while the Hub is on screen and the app is in
            // front, and again when either comes back.
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                model.controller.refreshLive()
                try? await Task.sleep(for: .seconds(60))
            }
        }
        .onDisappear { model.controller.setArranging(value: false) }
        .onChange(of: model.snapshot?.canvas.arranging ?? false) { _, arranging in
            if !arranging { draggingId = nil }
        }
    }

    private func open(_ tile: NativeHubTile) {
        guard let info = tile.info else {
            // A widget that was added but not set up yet opens its settings.
            if tile.people.isEmpty, tile.kind == "contacts" || tile.kind == "shortcut" {
                model.controller.editWidget(id: tile.id)
            }
            return
        }
        switch info.action {
        case "convert":
            converting = HubConverterTarget(id: tile.id, info: info)
        case "topup":
            model.controller.clearPpqTopUpError(widgetId: tile.id)
            toppingUp = HubPpqTarget(id: tile.id)
        case "claro":
            model.controller.openClaro(id: tile.id)
        case "setup":
            // Without a usable key or a number there is nothing to top up; the settings ask for it.
            model.controller.editWidget(id: tile.id)
        default:
            break
        }
    }

    /// The shop is open for as long as the shared state says so: it closes itself once Claro's
    /// invoice is with the wallet.
    private var claroPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot?.claro.open == true },
            set: { if !$0 { model.controller.closeClaro() } }
        )
    }

    private var editorPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot.map { $0.screen != "hub" } ?? false },
            set: { if !$0 { model.controller.close() } }
        )
    }

    private func canvas(_ state: NativePaymentHubSnapshot) -> some View {
        VStack(spacing: 0) {
            if let error = state.error, state.screen == "hub" {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, HubGeometry.gutter)
                    .padding(.bottom, 12)
            }

            GeometryReader { viewport in
                let columns = viewport.size.width >= 700 ? 4 : 2
                let width = min(viewport.size.width, 1100) - HubGeometry.gutter * 2
                let unit = (width - HubGeometry.gap * CGFloat(columns - 1)) / CGFloat(columns)
                ScrollView {
                    Group {
                        if state.canvas.tiles.isEmpty {
                            // A lone dashed square on an empty screen reads as a loading failure,
                            // so the first run says what the canvas is for.
                            emptyCanvas(state, unit: min(unit, 180))
                                .frame(minHeight: viewport.size.height)
                        } else {
                            widgetGrid(state, unit: unit)
                                .padding(.top, HubGeometry.gutter)
                                .padding(.bottom, 24)
                        }
                    }
                    .padding(.horizontal, HubGeometry.gutter)
                    .frame(maxWidth: 1100)
                    .frame(maxWidth: .infinity)
                    .disabled(state.busy)
                }
                .refreshable {
                    // Pulling down refreshes every widget at once; the system's refresh control
                    // gives the tick when the pull takes.
                    await withCheckedContinuation { done in
                        model.controller.refreshNow { done.resume() }
                    }
                }
                .onAppear { model.controller.setCanvasColumns(value: Int32(columns)) }
                .onChange(of: columns) { _, value in
                    model.controller.setCanvasColumns(value: Int32(value))
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .overlay(alignment: .topTrailing) {
            // Arranging ends with Done in the corner, as on the Home Screen; nothing on the Hub
            // moves for it, so a widget being dragged stays under the finger.
            if state.canvas.arranging, !state.canvas.tiles.isEmpty {
                Button(state.text.done) {
                    model.controller.setArranging(value: false)
                }
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .padding(.horizontal, 18)
                .frame(height: 36)
                .background(.regularMaterial, in: Capsule())
                .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
                .padding(.trailing, HubGeometry.gutter)
                .padding(.top, 4)
                .disabled(state.busy)
                .transition(.scale(scale: 0.85).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: state.canvas.arranging)
    }

    private func widgetGrid(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        let addPosition = addWidgetPosition(state.canvas)
        return ZStack(alignment: .topLeading) {
            ForEach(Array(state.canvas.tiles.enumerated()), id: \.element.id) { index, tile in
                let arranging = state.canvas.arranging
                let carried = draggingId == tile.id
                let shape = RoundedRectangle(cornerRadius: HubGeometry.corner, style: .continuous)
                WidgetContent(
                    tile: tile,
                    interactive: !arranging,
                    pay: { model.controller.pay(actionId: $0) },
                    open: open
                )
                .frame(
                    width: HubGeometry.span(unit, tile.columns),
                    height: HubGeometry.span(unit, tile.rows)
                )
                // While it is carried, the lifted copy under the finger is the widget.
                .opacity(carried ? 0 : 1)
                .overlay(alignment: .topLeading) {
                    if arranging, !carried {
                        Button { model.controller.removeWidget(id: tile.id) } label: {
                            Image(systemName: "minus")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.primary)
                                .frame(width: 26, height: 26)
                                .background(.regularMaterial, in: Circle())
                                .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .offset(x: -16, y: -16)
                        .transition(.scale.combined(with: .opacity))
                        .accessibilityLabel(state.text.remove + ", " + tile.title)
                    }
                }
                .modifier(HubWiggle(active: arranging && !carried, seed: index))
                // The lift and the drag keep the widget's own rounded shape.
                .contentShape(.contextMenuPreview, shape)
                .contentShape(.dragPreview, shape)
                .contentShape(shape)
                // A tap while arranging opens the widget's settings, as on the Home Screen.
                .gesture(
                    TapGesture().onEnded { model.controller.editWidget(id: tile.id) },
                    // Otherwise the widget's own buttons take the tap.
                    including: arranging ? .all : .subviews
                )
                .contextMenu {
                    if !arranging { widgetMenu(tile, state: state) }
                }
                .onDrag {
                    // iOS asks for the item as soon as the widget lifts for its menu, before
                    // anyone drags: only remember which widget it is. Carrying it starts once the
                    // drag really moves over the Hub (see HubTileDrop), after the menu is gone.
                    liftedWidget.id = tile.id
                    return HubDragItem.provider(for: tile.id) {
                        if liftedWidget.id == tile.id { liftedWidget.id = nil }
                        guard draggingId == tile.id else { return }
                        draggingId = nil
                        model.controller.finishMove(id: tile.id)
                    }
                } preview: {
                    WidgetContent(tile: tile, interactive: false, pay: { _ in })
                        .frame(
                            width: HubGeometry.span(unit, tile.columns),
                            height: HubGeometry.span(unit, tile.rows)
                        )
                        .contentShape(.dragPreview, shape)
                }
                .accessibilityAction(named: Text(state.text.editWidget)) {
                    model.controller.editWidget(id: tile.id)
                }
                .accessibilityAction(named: Text(state.text.moveUp)) {
                    model.controller.moveWidgetBy(id: tile.id, offset: -1)
                }
                .accessibilityAction(named: Text(state.text.moveDown)) {
                    model.controller.moveWidgetBy(id: tile.id, offset: 1)
                }
                .accessibilityAction(named: Text(state.text.remove)) {
                    model.controller.removeWidget(id: tile.id)
                }
                .offset(
                    x: (unit + HubGeometry.gap) * CGFloat(tile.column),
                    y: (unit + HubGeometry.gap) * CGFloat(tile.row)
                )
            }

            // Where a carried widget can land: one slot over each widget, apart from the widgets
            // themselves. iOS does not hand a drop to the view a drag started from, so a widget
            // let go over its own new place would otherwise never be put down.
            if draggingId != nil {
                ForEach(state.canvas.tiles, id: \.id) { tile in
                    Color.clear
                        .frame(
                            width: HubGeometry.span(unit, tile.columns),
                            height: HubGeometry.span(unit, tile.rows)
                        )
                        .contentShape(Rectangle())
                        .onDrop(
                            of: [.text],
                            delegate: HubTileDrop(
                                targetId: tile.id,
                                lifted: liftedWidget,
                                draggingId: $draggingId,
                                lastTargetId: $lastTargetId,
                                controller: model.controller
                            )
                        )
                        .offset(
                            x: (unit + HubGeometry.gap) * CGFloat(tile.column),
                            y: (unit + HubGeometry.gap) * CGFloat(tile.row)
                        )
                        .accessibilityHidden(true)
                }
            }

            // Arranging is about the widgets already placed, so adding one waits.
            if !state.canvas.arranging {
                addWidgetTile(state, unit: unit)
                    .offset(
                        x: (unit + HubGeometry.gap) * CGFloat(addPosition.column),
                        y: (unit + HubGeometry.gap) * CGFloat(addPosition.row)
                    )
                    .transition(.opacity)
            }
        }
        // Widgets glide to their new places as a carried one passes, or as one changes size.
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: placementKey(state.canvas))
        .animation(.easeInOut(duration: 0.2), value: state.canvas.arranging)
        // A tap on the Hub around the widgets ends arranging.
        .background {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture {
                    if state.canvas.arranging { model.controller.setArranging(value: false) }
                }
        }
        // A widget let go between the others keeps the place it was shown in.
        .onDrop(
            of: [.text],
            delegate: HubTileDrop(
                targetId: nil,
                lifted: liftedWidget,
                draggingId: $draggingId,
                lastTargetId: $lastTargetId,
                controller: model.controller
            )
        )
        .frame(
            maxWidth: .infinity,
            minHeight: HubGeometry.span(unit, max(state.canvas.rows, addPosition.row + 1)),
            alignment: .topLeading
        )
    }

    private func addWidgetTile(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        Button {
            model.controller.openGallery()
        } label: {
            VStack(spacing: 10) {
                // The disc sits where a contact portrait would, so the placeholder lines up with
                // its neighbours instead of floating.
                ZStack {
                    Circle().fill(Color(uiColor: .tertiarySystemFill))
                    Image(systemName: "plus").font(.title3.weight(.semibold))
                }
                .frame(width: 46, height: 46)
                .accessibilityHidden(true)
                Text(state.text.addWidget)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(.secondary)
            .padding(12)
            .frame(width: unit, height: unit)
            .background(
                Color(uiColor: .secondarySystemGroupedBackground).opacity(0.5),
                in: RoundedRectangle(cornerRadius: HubGeometry.corner)
            )
            .overlay {
                RoundedRectangle(cornerRadius: HubGeometry.corner)
                    .strokeBorder(
                        Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            }
            .contentShape(RoundedRectangle(cornerRadius: HubGeometry.corner))
        }
        .buttonStyle(.plain)
    }

    private func emptyCanvas(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        VStack(spacing: 10) {
            Spacer(minLength: 0)
            Text(state.text.emptyTitle)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(state.text.emptyBody)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
            addWidgetTile(state, unit: unit)
                .padding(.top, 14)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
    }

    /// What a long press on a widget offers, as on the Home Screen: its layouts as a row of their
    /// shapes, then editing it, arranging the Hub, and removing it.
    @ViewBuilder
    private func widgetMenu(_ tile: NativeHubTile, state: NativePaymentHubSnapshot) -> some View {
        if tile.sizes.count > 1 {
            Section {
                Picker(
                    state.text.chooseLayout,
                    selection: Binding(
                        get: { tile.variantId },
                        set: { model.controller.resizeWidget(id: tile.id, variantId: $0) }
                    )
                ) {
                    ForEach(tile.sizes, id: \.id) { size in
                        Label {
                            Text(size.title)
                        } icon: {
                            Image(uiImage: HubLayoutGlyph.image(columns: Int(size.columns), rows: Int(size.rows)))
                        }
                        .tag(size.id)
                    }
                }
                .pickerStyle(.palette)
            }
        }
        Section {
            Button(state.text.editWidget, systemImage: "info.circle") {
                model.controller.editWidget(id: tile.id)
            }
            Button(state.text.editHub, systemImage: "square.grid.2x2") {
                model.controller.setArranging(value: true)
            }
            Button(state.text.remove, systemImage: "minus.circle", role: .destructive) {
                model.controller.removeWidget(id: tile.id)
            }
        }
    }

    /// Where every widget sits, so a change of order or size animates.
    private func placementKey(_ canvas: NativeHubCanvas) -> [String] {
        canvas.tiles.map { "\($0.id)@\($0.column),\($0.row),\($0.columns)x\($0.rows)" }
    }

    private func addWidgetPosition(_ canvas: NativeHubCanvas) -> (column: Int32, row: Int32) {
        // Append on the final occupied row when it has room, otherwise begin the next row.
        let lastRow = max(0, canvas.rows - 1)
        for column in 0..<max(1, canvas.columns) {
            let occupied = canvas.tiles.contains { tile in
                column >= tile.column && column < tile.column + tile.columns && lastRow >= tile.row
                    && lastRow < tile.row + tile.rows
            }
            if !occupied { return (column, lastRow) }
        }
        return (0, canvas.rows)
    }
}

private struct HubEditorSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @State private var givingFeedback = false

    var body: some View {
        NavigationStack(path: navigationPath) {
            Group {
                if let state = model.snapshot {
                    gallery(state)
                        .navigationDestination(for: HubRoute.self) { route in
                            destination(route)
                        }
                } else {
                    ProgressView()
                }
            }
        }
        .sheet(isPresented: $givingFeedback) {
            HubFeedbackSheet(model: model)
        }
    }

    /// The way out of the gallery for someone who did not find their widget.
    private func feedbackPrompt(_ state: NativePaymentHubSnapshot) -> some View {
        Button {
            model.controller.clearFeedbackError()
            givingFeedback = true
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(state.text.feedbackPrompt).font(.headline).foregroundStyle(.primary)
                Text(state.feedback.sentSerial > 0 ? state.text.feedbackThanks : state.text.feedbackPromptBody)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.leading)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    private var routes: [HubRoute] {
        guard let state = model.snapshot else { return [] }
        switch state.screen {
        case "variants": return [.variants]
        case "configure": return [.variants, .configure]
        default: return []
        }
    }

    private var navigationPath: Binding<[HubRoute]> {
        Binding(
            get: { routes },
            set: { next in
                guard model.snapshot?.busy != true else { return }
                for _ in 0..<max(0, routes.count - next.count) {
                    model.controller.back()
                }
            }
        )
    }

    @ViewBuilder
    private func destination(_ route: HubRoute) -> some View {
        if let state = model.snapshot {
            switch route {
            case .variants:
                if let definition = state.selectedDefinition {
                    VariantGallery(state: state, definition: definition, controller: model.controller)
                } else {
                    unavailable(state)
                }
            case .configure:
                if let editor = state.editor, let definition = state.selectedDefinition {
                    WidgetConfigurationView(
                        model: model,
                        editor: editor,
                        definition: definition
                    )
                } else {
                    unavailable(state)
                }
            }
        }
    }

    private func unavailable(_ state: NativePaymentHubSnapshot) -> some View {
        ContentUnavailableView(state.text.unavailable, systemImage: "square.dashed")
            .toolbar { dismissButton(state.text.cancel) }
    }

    private func gallery(_ state: NativePaymentHubSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(state.text.galleryBody)
                    .font(.title3.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                gallerySection(state, title: state.text.sectionPeople, kinds: ["contacts", "shortcut"])
                gallerySection(state, title: state.text.sectionCollections, kinds: ["favorites", "recents"])
                gallerySection(state, title: state.text.sectionInfo, kinds: ["rate", "sats"])
                gallerySection(state, title: state.text.sectionAi, kinds: ["ppq"])
                gallerySection(state, title: state.text.sectionMobile, kinds: ["claro"])
                feedbackPrompt(state)
            }
            .padding(20)
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(state.text.galleryTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { dismissButton(state.text.cancel) }
    }

    private func gallerySection(_ state: NativePaymentHubSnapshot, title: String, kinds: [String]) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title3.bold()).accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14, alignment: .top), count: 2), spacing: 24) {
                ForEach(state.gallery.filter { kinds.contains($0.kind) }, id: \.id) { definition in
                    Button {
                        model.controller.selectDefinition(id: definition.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            if let preview = definition.variants.first?.preview {
                                WidgetContent(tile: preview, interactive: false, pay: { _ in }, compactPreview: true)
                                    .aspectRatio(1.05, contentMode: .fit)
                                    .allowsHitTesting(false)
                                    .accessibilityHidden(true)
                            }
                            Text(definition.title).font(.headline).foregroundStyle(.primary)
                            Text(definition.detail)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ToolbarContentBuilder
    private func dismissButton(_ label: String) -> some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.controller.close() } label: { Image(systemName: "xmark") }
                .accessibilityLabel(label)
                .disabled(model.snapshot?.busy == true)
        }
    }
}

private struct VariantGallery: View {
    let state: NativePaymentHubSnapshot
    let definition: NativeHubDefinition
    let controller: NativePaymentHubController

    private var selected: NativeHubVariant? {
        definition.variants.first { $0.id == state.editor?.variantId }
    }

    /// Every page reserves the tallest layout, so the previews keep their relative sizes and paging
    /// never resizes the screen around them.
    private var previewRows: CGFloat {
        CGFloat(definition.variants.map { $0.rows }.max() ?? 1)
    }

    var body: some View {
        GeometryReader { proxy in
            let gaps = HubGeometry.gap * (previewRows - 1)
            // Roughly half the screen for the preview, the rest for the copy and the page dots
            // around it. A share rather than a fixed reserve keeps both readable as text scales.
            let budget = max(proxy.size.height * 0.45, 160)
            let unit = min((min(proxy.size.width, 420) - 52) / 2, (budget - gaps) / previewRows, 160)
            let stage = unit * previewRows + gaps
            ScrollView {
                VStack(spacing: 14) {
                    Spacer(minLength: 0)
                    Text(definition.title)
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    Text(definition.detail)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    TabView(selection: Binding(
                        get: { state.editor?.variantId ?? definition.variants.first?.id ?? "" },
                        set: { controller.selectVariant(id: $0) }
                    )) {
                        ForEach(definition.variants, id: \.id) { variant in
                            WidgetContent(tile: variant.preview, interactive: false, pay: { _ in })
                                .frame(
                                    width: HubGeometry.span(unit, variant.columns),
                                    height: HubGeometry.span(unit, variant.rows)
                                )
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .tag(variant.id)
                                .accessibilityLabel(variant.title)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: definition.variants.count > 1 ? .always : .never))
                    .indexViewStyle(.page(backgroundDisplayMode: .always))
                    .frame(height: stage + (definition.variants.count > 1 ? 44 : 0))
                    if let selected {
                        Text(selected.title).font(.headline)
                        Text(selected.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
        .safeAreaInset(edge: .bottom) {
            // A widget that needs no setup is added from here, so the button says so.
            HubPrimaryButton(
                title: state.addsWithoutSetup ? state.text.addWidget : state.text.continueTitle,
                busy: state.busy
            ) {
                controller.continueFromVariants()
            }
        }
        // The bar says what to do here; the body already names what is being configured.
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(state.text.chooseLayout)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct WidgetConfigurationView: View {
    @ObservedObject var model: NativePaymentHubModel
    let editor: NativeHubEditor
    let definition: NativeHubDefinition
    @State private var showingContactPicker = false

    private var controller: NativePaymentHubController { model.controller }
    private var isPersonal: Bool { editor.kind == "contacts" || editor.kind == "shortcut" }

    var body: some View {
        Group {
            if let state = model.snapshot {
                Form {
                    if editor.showsName {
                        Section {
                            TextField(
                                state.text.widgetName,
                                text: Binding(
                                    get: { editor.title },
                                    set: { controller.updateTitle(value: $0) }
                                ))
                        }
                    }
                    if let preview = editor.preview {
                        // These widgets show no name, so the form opens on the widget itself.
                        Section {
                            WidgetContent(tile: preview, interactive: false, pay: { _ in })
                                .frame(maxWidth: preview.columns > 1 ? .infinity : 168)
                                .frame(height: 168)
                                .frame(maxWidth: .infinity)
                                .accessibilityHidden(true)
                                .listRowInsets(EdgeInsets())
                                .listRowBackground(Color.clear)
                        }
                    }
                    if !editor.currencyChoices.isEmpty {
                        Section {
                            Picker(
                                state.text.currency,
                                selection: Binding(
                                    get: { editor.currencyCode },
                                    set: { controller.selectCurrency(code: $0) }
                                )
                            ) {
                                ForEach(editor.currencyChoices, id: \.self) { code in
                                    Text(code).tag(code)
                                }
                            }
                        }
                    }
                    if let hint = editor.apiKeyHint {
                        Section {
                            SecureField(
                                "sk-…",
                                text: Binding(
                                    get: { editor.apiKey },
                                    set: { controller.updateApiKey(value: $0) }
                                )
                            )
                            .textContentType(.password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        } header: {
                            Text(state.text.apiKey)
                        } footer: {
                            Text(hint)
                        }
                        if let body = editor.signupBody {
                            Section {
                                Link(
                                    editor.signupAction,
                                    destination: URL(string: "https://ppq.ai/invite/52f36fe6")!
                                )
                            } footer: {
                                Text(body)
                            }
                        }
                    }
                    if let hint = editor.phoneHint {
                        Section {
                            HStack(spacing: 6) {
                                Text("+503").foregroundStyle(.secondary)
                                TextField(
                                    "7123 4567",
                                    text: Binding(
                                        get: { editor.phone },
                                        set: { controller.updatePhone(value: $0) }
                                    )
                                )
                                .keyboardType(.phonePad)
                                .textContentType(.telephoneNumber)
                                .monospacedDigit()
                            }
                        } header: {
                            Text(state.text.phone)
                        } footer: {
                            Text(hint)
                        }
                    }
                    if isPersonal {
                        contacts(state)
                    } else if editor.kind == "favorites" || editor.kind == "recents" {
                        Section {
                            Text(definition.detail)
                            Text(state.text.automaticHint).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if editor.kind == "shortcut" {
                        Section(state.text.amount) {
                            HStack {
                                TextField(
                                    state.text.amount,
                                    text: Binding(
                                        get: { editor.amount },
                                        set: { controller.updateAmount(value: $0) }
                                    )
                                )
                                .keyboardType(.decimalPad)
                                Picker(
                                    editor.currencyCode,
                                    selection: Binding(
                                        get: { editor.currencyCode },
                                        set: { controller.selectCurrency(code: $0) }
                                    )
                                ) {
                                    ForEach(editor.currencyCodes, id: \.self) { code in
                                        Text(code).tag(code)
                                    }
                                }
                                .labelsHidden()
                            }
                            if let hint = editor.fiatAmountHint {
                                Text(hint)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            TextField(
                                state.text.comment,
                                text: Binding(
                                    get: { editor.comment },
                                    set: { controller.updateComment(value: $0) }
                                ))
                        }
                    }
                }
                .disabled(state.busy)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 0) {
                        if let message = state.error ?? editor.validationMessage {
                            Text(message)
                                .font(.footnote)
                                .foregroundStyle(state.error == nil ? Color.secondary : Color.red)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                        }
                        HubPrimaryButton(
                            title: editor.isEditing ? state.text.save : state.text.addWidget,
                            busy: state.busy,
                            action: controller.saveWidget
                        )
                        .disabled(!editor.canSave)
                    }
                    .background(Color(uiColor: .systemGroupedBackground))
                }
                .navigationTitle(editor.isEditing ? state.text.editWidget : definition.title)
                .navigationBarTitleDisplayMode(.inline)
                .navigationBarBackButtonHidden(state.busy)
                .sheet(isPresented: $showingContactPicker) {
                    HubContactPicker(model: model)
                        .presentationDetents([.large])
                        .interactiveDismissDisabled(model.snapshot?.busy == true)
                }
            }
        }
    }

    @ViewBuilder
    private func contacts(_ state: NativePaymentHubSnapshot) -> some View {
        Section {
            ForEach(editor.selectedContacts, id: \.id) { contact in
                Button {
                    showingContactPicker = true
                } label: {
                    HStack(spacing: 12) {
                        HubAvatar(initials: contact.initials, size: 38)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(contact.title).foregroundStyle(.primary)
                            Text(contact.address).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if editor.selectedContacts.count < Int(editor.capacity) {
                Button {
                    showingContactPicker = true
                } label: {
                    Label(editor.selectionTitle, systemImage: "person.crop.circle.badge.plus")
                }
            }
        } header: {
            Text(state.text.contactsTitle)
        } footer: {
            // "0 of 1 selected" only restates the single row above it.
            if editor.capacity > 1 {
                Text(editor.selectionCount)
            }
        }
    }
}

private struct HubContactPicker: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var showingNewContact = false
    @State private var pendingDeletion: NativeHubContact?

    var body: some View {
        NavigationStack {
            if let state = model.snapshot, let editor = state.editor {
                let selected = editor.selectedContacts.filter { contact in
                    let query = state.query.trimmingCharacters(in: .whitespacesAndNewlines)
                    return query.isEmpty || contact.title.localizedCaseInsensitiveContains(query)
                        || contact.address.localizedCaseInsensitiveContains(query)
                }
                List {
                    Section {
                        ForEach(selected, id: \.id) { contact in
                            contactRow(contact, selected: true, state: state, editor: editor)
                                .contextMenu {
                                    if let index = editor.selectedContacts.firstIndex(where: {
                                        $0.id == contact.id
                                    }) {
                                        Button(state.text.moveUp, systemImage: "arrow.up") {
                                            model.controller.moveContact(id: contact.id, offset: -1)
                                        }.disabled(index == 0)
                                        Button(state.text.moveDown, systemImage: "arrow.down") {
                                            model.controller.moveContact(id: contact.id, offset: 1)
                                        }.disabled(index == editor.selectedContacts.count - 1)
                                    }
                                }
                        }
                        ForEach(editor.availableContacts, id: \.id) { contact in
                            contactRow(contact, selected: false, state: state, editor: editor)
                        }
                        if selected.isEmpty && editor.availableContacts.isEmpty {
                            Text(state.contactsEmpty ? state.text.noContacts : state.text.noMatches)
                                .foregroundStyle(.secondary)
                        }
                    } header: {
                        // A single-choice list needs no tally; the checkmark already says it.
                        if editor.capacity > 1 {
                            Text(editor.selectionCount)
                        }
                    } footer: {
                        if editor.capacity > 1,
                            editor.selectedContacts.count >= Int(editor.capacity)
                        {
                            Text(editor.selectionLimit)
                        }
                    }
                    if let error = state.error, !showingNewContact {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                }
                .disabled(state.busy)
                .searchable(
                    text: Binding(
                        get: { state.query },
                        set: { model.controller.updateQuery(value: $0) }
                    ), placement: .navigationBarDrawer(displayMode: .always), prompt: Text(state.text.search)
                )
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .scrollDismissesKeyboard(.interactively)
                .navigationTitle(editor.selectionTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            showingNewContact = true
                        } label: {
                            Label(state.text.addContact, systemImage: "person.crop.circle.badge.plus")
                        }
                        .disabled(
                            state.busy
                                || (editor.capacity > 1 && editor.selectedContacts.count >= editor.capacity))
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(state.text.done) { dismiss() }.disabled(state.busy)
                    }
                }
                .sheet(isPresented: $showingNewContact) {
                    AddHubContactSheet(model: model)
                        .interactiveDismissDisabled(model.snapshot?.busy == true)
                }
                .alert(
                    state.text.deleteContactTitle,
                    isPresented: Binding(
                        get: { pendingDeletion != nil },
                        set: { if !$0 { pendingDeletion = nil } }
                    ), presenting: pendingDeletion
                ) { contact in
                    Button(state.text.deleteContact, role: .destructive) {
                        model.controller.deleteContact(id: contact.id)
                        pendingDeletion = nil
                    }
                    Button(state.text.cancel, role: .cancel) { pendingDeletion = nil }
                } message: { contact in
                    Text(contact.title + "\n" + contact.address + "\n\n" + state.text.deleteContactBody)
                }
            }
        }
        .onAppear { model.controller.updateQuery(value: "") }
        .onDisappear { model.controller.updateQuery(value: "") }
    }

    private func contactRow(
        _ contact: NativeHubContact, selected: Bool, state: NativePaymentHubSnapshot, editor: NativeHubEditor
    ) -> some View {
        let atCapacity = editor.capacity > 1 && editor.selectedContacts.count >= editor.capacity
        return Button {
            model.controller.toggleContact(id: contact.id)
            if editor.capacity == 1 { dismiss() }
        } label: {
            HStack(spacing: 12) {
                HubAvatar(initials: contact.initials, size: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text(contact.title).foregroundStyle(.primary).lineLimit(1)
                    Text(contact.address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .opacity(!selected && atCapacity ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .disabled(!selected && atCapacity)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                pendingDeletion = contact
            } label: {
                Label(state.text.deleteContact, systemImage: "trash")
            }
            .tint(.red)
        }
    }
}

private struct AddHubContactSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var address = ""
    @State private var attemptedSave = false
    @State private var initialSerial: Int32

    init(model: NativePaymentHubModel) {
        self.model = model
        _initialSerial = State(initialValue: model.snapshot?.contactSavedSerial ?? 0)
    }

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
                Form {
                    Section {
                        TextField(state.text.name, text: $title)
                            .textContentType(.name)
                        TextField(state.text.address, text: $address)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    if attemptedSave, let error = state.error {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                }
                .disabled(state.busy)
                .navigationTitle(state.text.addContact)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(state.text.cancel) { dismiss() }
                            .disabled(state.busy)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(state.text.saveContact) {
                            attemptedSave = true
                            model.controller.addContact(title: title, address: address)
                        }
                        .disabled(state.busy)
                    }
                }
                .onChange(of: state.contactSavedSerial) { _, value in
                    if value != initialSerial { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct HubPrimaryButton: View {
    let title: String
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if busy { ProgressView().tint(.white) }
                Text(title).fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity, minHeight: 36)
        }
        .buttonStyle(.borderedProminent)
        .disabled(busy)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }
}

private struct WidgetContent: View {
    let tile: NativeHubTile
    let interactive: Bool
    let pay: (String) -> Void
    /// A tap on a widget that opens something of its own instead of paying a person.
    var open: (NativeHubTile) -> Void = { _ in }
    var compactPreview = false
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Group {
            if let info = tile.info {
                figure(info)
            } else if tile.people.isEmpty {
                empty
            } else if tile.kind == "shortcut", let person = tile.people.first {
                shortcut(person)
            } else if tile.columns == 1, let person = tile.people.first {
                single(person)
            } else {
                group
            }
        }
        .background {
            // Every tile is the same face, so a person, an amount and a price sit side by side as
            // one family. Colour comes from the portraits and from a widget's own glow.
            ZStack {
                LinearGradient(colors: [HubPalette.faceTop, HubPalette.faceBottom], startPoint: .top, endPoint: .bottom)
                if let glow = HubPalette.glow(tile.kind) {
                    // A trace of the widget's own colour, so a row of them is not one slab.
                    RadialGradient(
                        colors: [glow.opacity(colorScheme == .dark ? 0.26 : 0.16), glow.opacity(0)],
                        center: .topTrailing,
                        startRadius: 0,
                        endRadius: 190
                    )
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: HubGeometry.corner, style: .continuous))
        // By day a white face needs a little lift off the light page.
        .shadow(color: colorScheme == .dark ? .clear : HubPalette.shadow, radius: 8, y: 3)
    }

    private var small: Bool { tile.columns == 1 }
    /// A small tile centres what it shows; a wide one keeps it on the leading edge.
    private var bodyAlignment: Alignment { small ? .center : .leading }

    /// What every tile starts with: what it is about, and what a tap does in the far corner. Only
    /// a service brings its own mark.
    private func header<Trailing: View>(_ title: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: 7) {
            if let mark = HubPalette.mark(tile.kind) {
                Image(mark)
                    .resizable()
                    .scaledToFit()
                    .padding(4)
                    .frame(width: 26, height: 26)
                    .background(.white, in: Circle())
                    // By day the white disc would melt into a white face without a hairline.
                    .overlay { Circle().strokeBorder(HubPalette.hairline, lineWidth: 1) }
                    .accessibilityHidden(true)
            }
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(HubPalette.ink.opacity(0.8))
                .lineLimit(1)
            Spacer(minLength: 0)
            trailing()
        }
        .frame(minHeight: 26)
    }

    /// The one line under a tile's figure or portrait. An address keeps both of its ends when it
    /// is too long; words are cut at the end.
    private func caption(
        _ text: String, color: Color = HubPalette.ink.opacity(0.8), address: Bool = false
    ) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(color)
            .lineLimit(1)
            .truncationMode(address ? .middle : .tail)
            .frame(maxWidth: .infinity, alignment: bodyAlignment)
    }

    /// Quiet words in place of content a tile does not have yet.
    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(HubPalette.ink.opacity(0.6))
            .multilineTextAlignment(small ? .center : .leading)
            .minimumScaleFactor(0.85)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: bodyAlignment)
    }

    private func figureText(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 36, weight: .bold, design: .rounded).monospacedDigit())
            .lineLimit(1)
            .minimumScaleFactor(0.45)
            .frame(maxWidth: .infinity, alignment: bodyAlignment)
    }

    /// Stands where a figure will be while it is not known, like a system widget's placeholder.
    private var figurePlaceholder: some View {
        RoundedRectangle(cornerRadius: 9, style: .continuous)
            .fill(HubPalette.chip)
            .frame(width: 104, height: 30)
            .frame(height: 43)
            .frame(maxWidth: .infinity, alignment: bodyAlignment)
            .accessibilityHidden(true)
    }

    private var needsSetup: Bool { tile.kind == "contacts" || tile.kind == "shortcut" }

    private var empty: some View {
        HubTileAction(interactive: interactive && needsSetup, action: { open(tile) }) {
            VStack(alignment: .leading, spacing: 8) {
                header(tile.title) { EmptyView() }
                hint(tile.emptyText)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(HubPalette.ink)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
    }

    /// Rate, sats and ppq.ai share the shortcut's frame, so tiles side by side line up: what the
    /// figure is about on top, the figure in the middle, and one caption on the bottom line. A
    /// chart takes the middle and moves the figure up, or stands beside it on a row.
    private func figure(_ info: NativeHubInfo) -> some View {
        HubTileAction(interactive: interactive && info.action != "none", action: { open(tile) }) {
            VStack(alignment: .leading, spacing: 8) {
                header(info.headline) {
                    if info.hint == nil { figureAction(info) }
                }
                if let setup = info.hint {
                    hint(setup)
                } else {
                    // A small tile has no room for a chart that shows anything, so it keeps just
                    // the figure.
                    let points = small ? [] : info.chart.map { CGFloat($0.floatValue) }
                    if points.count < 2 {
                        centredFigure(info)
                    } else if tile.rows == 1 {
                        // A row is too short to stack a chart under the figure: it would come out
                        // flat, so the chart stands beside the figure and runs off the trailing edge.
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 8) { centredFigure(info) }
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                            HubSparkline(points: points, fadesIn: true)
                                .padding(.trailing, -14)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    } else {
                        figureValue(info)
                        // The day's prices fill the room below the price, edge to edge.
                        HubSparkline(points: points)
                            .padding(.horizontal, -14)
                            .frame(maxHeight: .infinity)
                        caption(info.caption, color: captionColor(info.captionTone))
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(HubPalette.ink)
            .contentShape(Rectangle())
        }
        .allowsHitTesting(interactive)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(info.accessibilityLabel)
        .accessibilityAddTraits(info.action == "none" ? [] : .isButton)
    }

    /// The figure in the middle of the room it has, and its caption on the bottom line.
    @ViewBuilder
    private func centredFigure(_ info: NativeHubInfo) -> some View {
        Spacer(minLength: 0)
        figureValue(info)
        Spacer(minLength: 0)
        caption(info.caption, color: captionColor(info.captionTone))
    }

    @ViewBuilder
    private func figureValue(_ info: NativeHubInfo) -> some View {
        if let value = info.value {
            figureText(value)
        } else {
            figurePlaceholder
        }
    }

    /// What a tap does, in the corner where a shortcut shows its bolt. A wide tile names it.
    @ViewBuilder
    private func figureAction(_ info: NativeHubInfo) -> some View {
        let symbol = info.action == "convert" ? "arrow.up.arrow.down" : "plus"
        if info.action == "none" {
            EmptyView()
        } else if tile.columns > 1, !info.actionTitle.isEmpty {
            Label(info.actionTitle, systemImage: symbol)
                .font(.footnote.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 10)
                .frame(height: 26)
                .background(HubPalette.chip, in: Capsule())
        } else {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .bold))
                .frame(width: 26, height: 26)
                .background(HubPalette.chip, in: Circle())
        }
    }

    private func captionColor(_ tone: String) -> Color {
        switch tone {
        case "up": return HubPalette.up
        case "down": return HubPalette.down
        case "accent": return HubPalette.ink
        default: return HubPalette.ink.opacity(0.8)
        }
    }

    private func shortcut(_ person: NativeHubPerson) -> some View {
        HubTileAction(interactive: interactive, action: { pay(person.actionId) }) {
            VStack(alignment: .leading, spacing: 8) {
                header(tile.title) {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 13, weight: .bold))
                        .frame(width: 26, height: 26)
                        .background(HubPalette.chip, in: Circle())
                        .accessibilityHidden(true)
                }
                Spacer(minLength: 0)
                figureText(person.amount ?? "")
                Spacer(minLength: 0)
                // Where the money goes, on the line where a contact shows its address.
                caption(person.address.isEmpty ? person.contactTitle : person.address, address: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(HubPalette.ink)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .allowsHitTesting(interactive)
        .accessibilityLabel([person.contactTitle, person.amount, tile.title].compactMap { $0 }.joined(separator: ", "))
    }

    /// One person, framed like a shortcut: the name as the tile's title, the portrait where the
    /// figure is, and the address on the bottom line.
    private func single(_ person: NativeHubPerson) -> some View {
        HubTileAction(interactive: interactive, action: { pay(person.actionId) }) {
            VStack(alignment: .leading, spacing: 8) {
                header(tile.customTitle ?? person.title) { EmptyView() }
                GeometryReader { proxy in
                    HubAvatar(initials: person.initials, size: max(24, min(proxy.size.width, proxy.size.height, 72)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                caption(
                    person.address.isEmpty || person.address == person.title ? tile.title : person.address,
                    address: true
                )
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(HubPalette.ink)
            .contentShape(Rectangle())
        }
        .allowsHitTesting(interactive)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(person.title)
        .accessibilityAddTraits(.isButton)
    }

    /// Several people under the tile's title, always as lines of portrait and name so every
    /// collection reads the same. A few get the tile's width and show what they were last paid at
    /// the far end; more share it in two columns.
    private var group: some View {
        let card = tile.rows >= 2 && !compactPreview
        let people = Array(tile.people.prefix(compactPreview ? 2 : card ? 6 : 4))
        let paired = people.count > (card ? 3 : 2)
        let avatar: CGFloat = card ? 44 : 34
        let gap: CGFloat = card ? 12 : 6
        return VStack(alignment: .leading, spacing: 8) {
            header(tile.title) { EmptyView() }
            Group {
                if paired {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .leading), count: 2),
                        alignment: .leading,
                        spacing: gap
                    ) {
                        ForEach(Array(people.enumerated()), id: \.element.id) { index, person in
                            personLine(person, avatarSize: avatar, seed: index, stacked: true)
                        }
                    }
                } else {
                    VStack(spacing: gap) {
                        ForEach(Array(people.enumerated()), id: \.element.id) { index, person in
                            personLine(person, avatarSize: avatar, seed: index, stacked: false)
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .foregroundStyle(HubPalette.ink)
        .accessibilityElement(children: .contain)
    }

    private func personLine(_ person: NativeHubPerson, avatarSize: CGFloat, seed: Int, stacked: Bool) -> some View {
        HubTileAction(interactive: interactive, action: {
            if !person.actionId.isEmpty { pay(person.actionId) }
        }) {
            HStack(spacing: 10) {
                HubAvatar(initials: person.initials, size: avatarSize, seed: seed)
                VStack(alignment: .leading, spacing: 1) {
                    Text(person.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    if stacked, let amount = person.amount {
                        Text(amount)
                            .font(.caption)
                            .foregroundStyle(HubPalette.ink.opacity(0.7))
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if !stacked, let amount = person.amount {
                    Text(amount)
                        .font(.subheadline)
                        .foregroundStyle(HubPalette.ink.opacity(0.8))
                        .monospacedDigit()
                        .lineLimit(1)
                        .layoutPriority(1)
                }
            }
            .contentShape(Rectangle())
        }
        .allowsHitTesting(interactive)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(person.title + (person.amount.map { ", " + $0 } ?? ""))
        .accessibilityAddTraits(.isButton)
    }
}

/// The widget iOS has lifted, which becomes the carried one once its drag moves.
private final class HubLiftedWidget {
    var id: String?
}

/// Starts carrying a lifted widget once its drag moves over the Hub, moves it into the place of
/// the one it passes over, and keeps the order where it is let go. With no target it stands for
/// the Hub around and between the widgets.
private struct HubTileDrop: DropDelegate {
    let targetId: String?
    let lifted: HubLiftedWidget
    @Binding var draggingId: String?
    @Binding var lastTargetId: String?
    let controller: NativePaymentHubController

    private var carried: String? { draggingId ?? lifted.id }

    func dropEntered(info: DropInfo) {
        startCarrying()
        guard let dragged = draggingId, let target = targetId else { return }
        if target == dragged {
            lastTargetId = nil
            return
        }
        guard target != lastTargetId else { return }
        lastTargetId = target
        controller.previewMove(id: dragged, targetId: target)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        startCarrying()
        return DropProposal(operation: carried == nil ? .forbidden : .move)
    }

    func validateDrop(info: DropInfo) -> Bool { carried != nil }

    func performDrop(info: DropInfo) -> Bool {
        guard let dragged = carried else { return false }
        draggingId = nil
        lastTargetId = nil
        lifted.id = nil
        controller.finishMove(id: dragged)
        return true
    }

    /// The drag has really started: the widget is carried and the Hub arranges. This waits a
    /// turn of the main queue, since iOS is still putting its menu away when the drag begins.
    private func startCarrying() {
        guard draggingId == nil, let id = lifted.id else { return }
        let draggingId = $draggingId
        let lastTargetId = $lastTargetId
        let controller = controller
        DispatchQueue.main.async {
            guard draggingId.wrappedValue == nil else { return }
            draggingId.wrappedValue = id
            lastTargetId.wrappedValue = nil
            controller.setArranging(value: true)
        }
    }
}

/// The item a carried widget travels as. A drag let go outside the Hub calls no drop delegate,
/// so the end of the drag is noticed when the system lets go of the item.
private enum HubDragItem {
    private final class EndWatcher: NSObject {
        let onEnd: () -> Void
        init(onEnd: @escaping () -> Void) { self.onEnd = onEnd }
        deinit {
            let onEnd = onEnd
            DispatchQueue.main.async { onEnd() }
        }
    }

    private static var watcherKey: UInt8 = 0

    static func provider(for id: String, onEnd: @escaping () -> Void) -> NSItemProvider {
        let provider = NSItemProvider(object: id as NSString)
        objc_setAssociatedObject(provider, &watcherKey, EndWatcher(onEnd: onEnd), .OBJC_ASSOCIATION_RETAIN)
        return provider
    }
}

/// The gentle wiggle of an arranged widget, as on the Home Screen; still with Reduce Motion.
private struct HubWiggle: ViewModifier {
    let active: Bool
    let seed: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tilted = false

    private var wiggles: Bool { active && !reduceMotion }

    func body(content: Content) -> some View {
        content
            .rotationEffect(.degrees(wiggles ? (tilted ? 0.9 : -0.9) : 0))
            .onChange(of: wiggles, initial: true) { _, on in
                guard on else {
                    withAnimation(.easeOut(duration: 0.15)) { tilted = false }
                    return
                }
                tilted = false
                // Each widget keeps its own pace, so the Hub never sways as one.
                let pace = 0.15 + Double(seed % 3) * 0.012
                withAnimation(.easeInOut(duration: pace).repeatForever(autoreverses: true).delay(Double(seed % 4) * 0.03)) {
                    tilted = true
                }
            }
    }
}

/// A layout drawn as its shape, for the menu's row of layouts: a small square, a wide one, a
/// large one.
private enum HubLayoutGlyph {
    private static var cache: [String: UIImage] = [:]

    static func image(columns: Int, rows: Int) -> UIImage {
        let key = "\(columns)x\(rows)"
        if let image = cache[key] { return image }
        let canvas = CGSize(width: 26, height: 26)
        let cell = canvas.width / 2.2
        let width = cell * CGFloat(columns) + CGFloat(columns - 1) * cell * 0.2
        let height = cell * CGFloat(rows) + CGFloat(rows - 1) * cell * 0.2
        let rect = CGRect(
            x: (canvas.width - width) / 2, y: (canvas.height - height) / 2, width: width, height: height
        ).insetBy(dx: 0.8, dy: 0.8)
        let image = UIGraphicsImageRenderer(size: canvas).image { _ in
            let path = UIBezierPath(roundedRect: rect, cornerRadius: cell * 0.3)
            path.lineWidth = 1.6
            UIColor.black.setStroke()
            path.stroke()
        }.withRenderingMode(.alwaysTemplate)
        cache[key] = image
        return image
    }
}

/// The day's prices as one soft line that runs off both edges of the tile, fading out below. A
/// chart that starts inside the tile can fade its fill in, so it has no hard edge beside the figure.
private struct HubSparkline: View {
    let points: [CGFloat]
    var fadesIn = false

    var body: some View {
        GeometryReader { proxy in
            let line = line(in: proxy.size)
            ZStack {
                area(under: line, in: proxy.size)
                    .fill(
                        LinearGradient(
                            colors: [HubPalette.ink.opacity(0.14), HubPalette.ink.opacity(0)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .mask {
                        LinearGradient(
                            stops: [
                                .init(color: fadesIn ? .clear : .black, location: 0),
                                .init(color: .black, location: min(1, 32 / max(proxy.size.width, 1)))
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    }
                line.stroke(
                    HubPalette.ink.opacity(0.92),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                )
            }
        }
        .accessibilityHidden(true)
    }

    private func line(in size: CGSize) -> Path {
        // Room for the stroke above and below.
        let top: CGFloat = 2
        let drop = size.height - top - 2
        let step = size.width / CGFloat(max(points.count - 1, 1))
        func at(_ index: Int) -> CGPoint {
            CGPoint(x: step * CGFloat(index), y: top + (1 - points[index]) * drop)
        }
        var path = Path()
        guard points.count >= 2 else { return path }
        path.move(to: at(0))
        for index in 1..<points.count {
            let from = at(index - 1)
            let to = at(index)
            let middle = (from.x + to.x) / 2
            path.addCurve(
                to: to,
                control1: CGPoint(x: middle, y: from.y),
                control2: CGPoint(x: middle, y: to.y)
            )
        }
        return path
    }

    private func area(under line: Path, in size: CGSize) -> Path {
        var path = line
        path.addLine(to: CGPoint(x: size.width, y: size.height))
        path.addLine(to: CGPoint(x: 0, y: size.height))
        path.closeSubpath()
        return path
    }
}

/// A two-field calculator: type in either field and the other follows. It opens empty with one
/// unit of the currency as the example, so there is nothing to clear before typing.
private struct HubConverterSheet: View {
    private enum Field { case fiat, sats }

    let target: HubConverterTarget
    let text: NativePaymentHubCopy
    let controller: NativePaymentHubController
    @Environment(\.dismiss) private var dismiss
    @State private var fiat = ""
    @State private var sats = ""
    @FocusState private var focus: Field?

    private var code: String { target.info.currencyCode }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    field(
                        Binding(
                            get: { fiat },
                            set: { show(controller.convertFiat(currencyCode: code, input: $0)) }
                        ),
                        placeholder: "1",
                        unit: code,
                        keyboard: target.info.wholeUnits ? .numberPad : .decimalPad,
                        field: .fiat
                    )
                    field(
                        Binding(
                            get: { sats },
                            set: { show(controller.convertSats(currencyCode: code, input: $0)) }
                        ),
                        placeholder: target.info.converterPlaceholder,
                        unit: text.satsUnit,
                        keyboard: .numberPad,
                        field: .sats
                    )
                } footer: {
                    Text(target.info.converterNote)
                }
            }
            .navigationTitle(text.convert)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(text.done) { dismiss() }
                }
            }
        }
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
        .onAppear { focus = .fiat }
    }

    private func show(_ values: NativeHubConversion) {
        fiat = values.fiat
        sats = values.sats
    }

    private func field(
        _ value: Binding<String>, placeholder: String, unit: String, keyboard: UIKeyboardType,
        field: Field
    ) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            TextField(placeholder, text: value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .keyboardType(keyboard)
                .focused($focus, equals: field)
                .accessibilityLabel(unit)
            Text(unit).font(.headline).foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }
}

/// The PPQ.AI widget whose account the top-up sheet is for.
private struct HubPpqTarget: Identifiable {
    let id: String
}

/// Picks an amount; the wallet's own payment screen takes over once ppq.ai sent the invoice.
private struct HubPpqTopUpSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    let widgetId: String
    @Environment(\.dismiss) private var dismiss
    @State private var amount = "10"
    @State private var openedAt: Int32

    init(model: NativePaymentHubModel, widgetId: String) {
        self.model = model
        self.widgetId = widgetId
        _openedAt = State(initialValue: model.snapshot?.ppq[widgetId]?.topUpSerial ?? 0)
    }

    /// Each PPQ.AI widget has its own account; a widget removed meanwhile shows an empty one.
    private func account(_ state: NativePaymentHubSnapshot) -> NativeHubPpq {
        state.ppq[widgetId] ?? NativeHubPpq(
            balance: nil, presets: [], toppingUp: false, topUpError: nil, topUpSerial: 0
        )
    }

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
                let account = account(state)
                Form {
                    Section {
                        HStack(spacing: 14) {
                            Image("hub_ppq")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 44, height: 44)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("PPQ.AI").font(.headline)
                                if let balance = account.balance {
                                    Text(balance)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                        .monospacedDigit()
                                }
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    Section {
                        HStack(spacing: 8) {
                            ForEach(account.presets, id: \.self) { preset in
                                Button {
                                    amount = preset
                                } label: {
                                    Text("$" + preset)
                                        .font(.subheadline.weight(.semibold))
                                        .frame(maxWidth: .infinity, minHeight: 32)
                                }
                                .buttonStyle(.bordered)
                                .tint(amount == preset ? Color.accentColor : Color.secondary)
                                .accessibilityAddTraits(amount == preset ? .isSelected : [])
                            }
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    }
                    Section {
                        HStack(spacing: 6) {
                            Text("$").foregroundStyle(.secondary)
                            TextField(state.text.topUpAmount, text: $amount)
                                .keyboardType(.decimalPad)
                                .monospacedDigit()
                        }
                    } footer: {
                        Text(account.topUpError ?? state.text.topUpBody)
                            .foregroundStyle(account.topUpError == nil ? Color.secondary : Color.red)
                    }
                }
                .disabled(account.toppingUp)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom) {
                    HubPrimaryButton(title: state.text.topUpAction, busy: account.toppingUp) {
                        model.controller.topUpPpq(widgetId: widgetId, amount: amount)
                    }
                }
                .navigationTitle(state.text.topUpTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel(state.text.cancel)
                            .disabled(account.toppingUp)
                    }
                }
                .onChange(of: amount) { _, _ in model.controller.clearPpqTopUpError(widgetId: widgetId) }
                .onChange(of: account.topUpSerial) { _, value in
                    if value != openedAt { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.snapshot?.ppq[widgetId]?.toppingUp == true)
    }
}

/// What Claro sells to one number. Choosing is one tap and paying another, because the wallet may
/// pay a small invoice without asking again. Its own payment screen takes over once Claro sent
/// the invoice.
private struct HubClaroSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var chosen: String?

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
                let shop = state.claro
                Group {
                    if shop.loading {
                        ProgressView()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if let problem = shop.problem {
                        ContentUnavailableView {
                            Label(problem, systemImage: "antenna.radiowaves.left.and.right.slash")
                        } actions: {
                            // Asking again changes nothing for a number Claro does not sell to.
                            if shop.canRetry {
                                Button(state.text.claroRetry) { model.controller.retryClaro() }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    } else {
                        List {
                            Section {
                                HStack(spacing: 14) {
                                    Image("hub_claro")
                                        .resizable()
                                        .scaledToFit()
                                        .padding(7)
                                        .frame(width: 44, height: 44)
                                        .background(.white, in: Circle())
                                        .overlay { Circle().strokeBorder(HubPalette.hairline, lineWidth: 1) }
                                        .accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("Claro").font(.headline)
                                        Text(shop.phone)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                            .monospacedDigit()
                                    }
                                }
                                .accessibilityElement(children: .combine)
                            }
                            products(state.text.claroTopUps, shop.topUps)
                            products(state.text.claroPackages, shop.packages)
                        }
                        .disabled(shop.ordering)
                        .safeAreaInset(edge: .bottom) {
                            VStack(spacing: 0) {
                                // Who sells what is bought here, and under whose terms.
                                VStack(alignment: .leading, spacing: 4) {
                                    if let error = shop.orderError {
                                        Text(error).foregroundStyle(.red)
                                    }
                                    Text(state.text.claroNotice).foregroundStyle(.secondary)
                                    if let terms = URL(string: shop.termsUrl) {
                                        Link(state.text.claroTerms, destination: terms)
                                    }
                                }
                                .font(.footnote)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 20)
                                .padding(.top, 10)
                                HubPrimaryButton(title: state.text.claroPay, busy: shop.ordering) {
                                    if let chosen { model.controller.buyClaro(productId: chosen) }
                                }
                                .disabled(chosen == nil)
                            }
                            .background(.bar)
                        }
                    }
                }
                .navigationTitle(state.text.claroTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel(state.text.cancel)
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func products(_ title: String, _ products: [NativeHubClaroProduct]) -> some View {
        if !products.isEmpty {
            Section(title) {
                ForEach(products, id: \.id) { product in
                    let isChosen = chosen == product.id
                    Button {
                        chosen = product.id
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(product.name)
                                    .font(.body.weight(.medium))
                                if let detail = product.detail {
                                    Text(detail)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 0)
                            Text(product.price)
                                .font(.body.weight(.semibold))
                                .monospacedDigit()
                            Image(systemName: isChosen ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(isChosen ? Color.accentColor : Color.secondary)
                                .accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isChosen ? .isSelected : [])
                }
            }
        }
    }
}

/// Where a tester tells the developer what the Hub is missing.
private struct HubFeedbackSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var kind = "widget"
    @State private var message = ""
    @State private var contact = ""
    @State private var openedAt: Int32

    init(model: NativePaymentHubModel) {
        self.model = model
        _openedAt = State(initialValue: model.snapshot?.feedback.sentSerial ?? 0)
    }

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
                let kinds = [
                    ("widget", state.text.feedbackKindWidget),
                    ("idea", state.text.feedbackKindIdea),
                    ("problem", state.text.feedbackKindProblem),
                    ("other", state.text.feedbackKindOther),
                ]
                Form {
                    Section {
                        Picker(state.text.feedbackTitle, selection: $kind) {
                            ForEach(kinds, id: \.0) { Text($0.1).tag($0.0) }
                        }
                        .pickerStyle(.segmented)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    } footer: {
                        Text(state.text.feedbackBody)
                    }
                    Section {
                        TextField(state.text.feedbackMessage, text: $message, axis: .vertical)
                            .lineLimit(5...10)
                    }
                    Section {
                        TextField(state.text.feedbackContact, text: $contact)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        if state.feedback.failed {
                            Text(state.text.feedbackError).foregroundStyle(.red)
                        } else {
                            Text(state.text.feedbackContactHint)
                        }
                    }
                }
                .disabled(state.feedback.sending)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom) {
                    HubPrimaryButton(title: state.text.feedbackSend, busy: state.feedback.sending) {
                        if !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            model.controller.sendFeedback(kind: kind, message: String(message.prefix(2000)), contact: String(contact.prefix(200)))
                        }
                    }
                }
                .navigationTitle(state.text.feedbackTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel(state.text.cancel)
                            .disabled(state.feedback.sending)
                    }
                }
                .onChange(of: message) { _, _ in model.controller.clearFeedbackError() }
                .onChange(of: state.feedback.sentSerial) { _, value in
                    if value != openedAt { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(model.snapshot?.feedback.sending == true)
    }
}

/// Previews are pictures, so their enclosing gallery button owns all interaction.
private struct HubTileAction<Content: View>: View {
    let interactive: Bool
    let action: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        if interactive {
            Button(action: action, label: content).buttonStyle(.plain)
        } else {
            content()
        }
    }
}

private struct HubAvatar: View {
    let initials: String
    let size: CGFloat
    var seed: Int = 0

    private var color: Color {
        // Four quiet relatives of the tile's navy: enough to tell people apart, never louder than a figure.
        let colors: [Color] = [Color(red: 0.36, green: 0.63, blue: 0.69), Color(red: 0.52, green: 0.49, blue: 0.82), Color(red: 0.40, green: 0.56, blue: 0.80), Color(red: 0.76, green: 0.52, blue: 0.62)]
        let value = initials.isEmpty ? seed : initials.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return colors[value % colors.count]
    }

    var body: some View {
        ZStack {
            Circle().fill(color.gradient)
            if initials.isEmpty {
                Image(systemName: "person.fill").font(.system(size: size * 0.45, weight: .medium))
            } else {
                Text(initials).font(.system(size: size * 0.36, weight: .medium, design: .rounded))
            }
        }
        .foregroundStyle(.white)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
