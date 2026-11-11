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

private enum HubPalette {
    /// Bitcoin's own orange marks the widgets that are about bitcoin rather than a person.
    static let bitcoin = Color(red: 0.97, green: 0.58, blue: 0.10)
    static let up = Color(red: 0.37, green: 0.88, blue: 0.55)
    static let down = Color(red: 1.0, green: 0.54, blue: 0.50)
    static let ember = Color(red: 1.0, green: 0.36, blue: 0.20)

    static func glow(_ kind: String) -> Color? {
        switch kind {
        case "sats": return bitcoin
        case "ppq": return ember
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
    @State private var hoveredId: String?
    @State private var converting: HubConverterTarget?
    @State private var toppingUp = false
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
        .sheet(isPresented: $toppingUp) {
            HubPpqTopUpSheet(model: model)
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
    }

    private func open(_ tile: NativeHubTile) {
        guard let info = tile.info else { return }
        switch info.action {
        case "convert":
            converting = HubConverterTarget(id: tile.id, info: info)
        case "topup":
            model.controller.clearPpqTopUpError()
            toppingUp = true
        case "setup":
            // Without a usable key there is nothing to top up; the widget's settings ask for one.
            model.controller.editWidget(id: tile.id)
        default:
            break
        }
    }

    private var editorPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot.map { $0.screen != "hub" } ?? false },
            set: { if !$0 { model.controller.close() } }
        )
    }

    private func canvas(_ state: NativePaymentHubSnapshot) -> some View {
        VStack(spacing: 0) {
            if !state.canvas.tiles.isEmpty {
                HStack(spacing: 12) {
                    if state.canvas.arranging {
                        Text(state.text.editHub).font(.headline).accessibilityAddTraits(.isHeader)
                    }
                    Spacer()
                    Button { model.controller.openGallery() } label: {
                        Image(systemName: "plus").frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(state.text.addWidget)
                    .buttonStyle(.plain)
                    Button(state.canvas.arranging ? state.text.done : state.text.edit) {
                        model.controller.setArranging(value: !state.canvas.arranging)
                    }
                    .font(.body.weight(.semibold))
                    .frame(minHeight: 44)
                }
                .padding(.horizontal, HubGeometry.gutter)
                .disabled(state.busy)
            }
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
                .onAppear { model.controller.setCanvasColumns(value: Int32(columns)) }
                .onChange(of: columns) { _, value in
                    model.controller.setCanvasColumns(value: Int32(value))
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }

    private func widgetGrid(_ state: NativePaymentHubSnapshot, unit: CGFloat) -> some View {
        let addPosition = addWidgetPosition(state.canvas)
        return ZStack(alignment: .topLeading) {
            ForEach(Array(state.canvas.tiles.enumerated()), id: \.element.id) { index, tile in
                WidgetContent(
                    tile: tile,
                    interactive: !state.canvas.arranging,
                    pay: { model.controller.pay(actionId: $0) },
                    open: open
                )
                .frame(
                    width: HubGeometry.span(unit, tile.columns),
                    height: HubGeometry.span(unit, tile.rows)
                )
                .overlay {
                    if hoveredId == tile.id {
                        RoundedRectangle(cornerRadius: HubGeometry.corner)
                            .strokeBorder(Color.accentColor, lineWidth: 3)
                    }
                }
                .overlay(alignment: .topLeading) {
                    if state.canvas.arranging {
                        Button { model.controller.removeWidget(id: tile.id) } label: {
                            Image(systemName: "minus")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.primary)
                                .frame(width: 28, height: 28)
                                .background(.regularMaterial, in: Circle())
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .offset(x: -10, y: -10)
                        .accessibilityLabel(state.text.remove + ", " + tile.title)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if state.canvas.arranging {
                        Menu { widgetActions(tile, index: index, state: state) } label: {
                            Image(systemName: "ellipsis")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.primary)
                                .frame(width: 28, height: 28)
                                .background(.regularMaterial, in: Circle())
                                .frame(width: 44, height: 44)
                        }
                        .offset(x: 10, y: -10)
                        .accessibilityLabel(state.text.widgetOptions + ", " + tile.title)
                    }
                }
                .contextMenu { widgetActions(tile, index: index, state: state) }
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
                .draggable(tile.id)
                .dropDestination(for: String.self) { ids, _ in
                    guard !state.busy, ids.count == 1, let id = ids.first,
                        id != tile.id, state.canvas.tiles.contains(where: { $0.id == id })
                    else {
                        return false
                    }
                    model.controller.moveWidget(id: id, onto: tile.id)
                    hoveredId = nil
                    return true
                } isTargeted: { targeted in
                    if targeted {
                        hoveredId = tile.id
                    } else if hoveredId == tile.id {
                        hoveredId = nil
                    }
                }
                .offset(
                    x: (unit + HubGeometry.gap) * CGFloat(tile.column),
                    y: (unit + HubGeometry.gap) * CGFloat(tile.row)
                )
            }

            addWidgetTile(state, unit: unit)
                .offset(
                    x: (unit + HubGeometry.gap) * CGFloat(addPosition.column),
                    y: (unit + HubGeometry.gap) * CGFloat(addPosition.row)
                )
        }
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

    @ViewBuilder
    private func widgetActions(_ tile: NativeHubTile, index: Int, state: NativePaymentHubSnapshot)
        -> some View
    {
        Button(state.text.editWidget, systemImage: "slider.horizontal.3") {
            model.controller.editWidget(id: tile.id)
        }
        if !state.canvas.arranging {
            Button(state.text.editHub, systemImage: "square.grid.2x2") {
                model.controller.setArranging(value: true)
            }
        }
        if tile.sizes.count > 1 {
            Menu(state.text.chooseLayout) {
                ForEach(tile.sizes, id: \.id) { size in
                    Button {
                        model.controller.resizeWidget(id: tile.id, variantId: size.id)
                    } label: {
                        if size.id == tile.variantId {
                            Label(size.title, systemImage: "checkmark")
                        } else {
                            Text(size.title)
                        }
                    }
                }
            }
        }
        Button(state.text.moveUp, systemImage: "arrow.up") {
            model.controller.moveWidgetBy(id: tile.id, offset: -1)
        }
        .disabled(index == 0)
        Button(state.text.moveDown, systemImage: "arrow.down") {
            model.controller.moveWidgetBy(id: tile.id, offset: 1)
        }
        .disabled(index == state.canvas.tiles.count - 1)
        Divider()
        Button(state.text.remove, systemImage: "minus.circle", role: .destructive) {
            model.controller.removeWidget(id: tile.id)
        }
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
    @ScaledMetric(relativeTo: .caption) private var nameLine: CGFloat = 22
    @ScaledMetric(relativeTo: .caption2) private var amountLine: CGFloat = 15

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
            // Every tile is the same dark face, so a person, an amount and a price sit side by side
            // as one family. Colour comes from the portraits and from a widget's own glow.
            ZStack {
                LinearGradient(colors: [Color(red: 0.16, green: 0.16, blue: 0.30), Color(red: 0.06, green: 0.08, blue: 0.13)], startPoint: .top, endPoint: .bottom)
                if let glow = HubPalette.glow(tile.kind) {
                    // A trace of the widget's own colour, so a row of them is not one slab.
                    RadialGradient(
                        colors: [glow.opacity(0.26), glow.opacity(0)],
                        center: .topTrailing,
                        startRadius: 0,
                        endRadius: 190
                    )
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: HubGeometry.corner, style: .continuous))
    }

    /// What every people tile starts with, in the place and styles of a figure tile's header.
    private var peopleHeader: some View {
        HStack(spacing: 7) {
            Image(systemName: peopleSymbol)
                .font(.system(size: 12, weight: .bold))
                .frame(width: 26, height: 26)
                .background(.white.opacity(0.14), in: Circle())
                .accessibilityHidden(true)
            Text(tile.title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.8))
                .lineLimit(1)
            Spacer(minLength: 0)
        }
    }

    private var peopleSymbol: String {
        switch tile.kind {
        case "favorites": return "star.fill"
        case "recents": return "clock.fill"
        case "shortcut": return "bolt.fill"
        default: return "person.2.fill"
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 8) {
            peopleHeader
            Spacer(minLength: 0)
            Text(tile.emptyText)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.8))
                .minimumScaleFactor(0.85)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .foregroundStyle(.white)
    }

    /// Rate, sats and ppq.ai share the shortcut's frame, so tiles side by side line up: what the
    /// figure is about on top, then the figure and one caption line on the same baselines.
    private func figure(_ info: NativeHubInfo) -> some View {
        HubTileAction(interactive: interactive && info.action != "none", action: { open(tile) }) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 7) {
                    figureBadge
                    Text(info.headline)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    figureAction(info)
                }
                if info.chart.count >= 2 {
                    // The day's prices fill the room between the two, edge to edge.
                    HubSparkline(points: info.chart.map { CGFloat($0.floatValue) })
                        .padding(.horizontal, -14)
                        .padding(.top, 4)
                        .frame(maxHeight: .infinity)
                } else {
                    Spacer(minLength: 0)
                }
                Text(info.value ?? "—")
                    .font(.system(size: 36, weight: .bold, design: .rounded).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.45)
                    .opacity(info.value == nil ? 0.4 : 1)
                Text(info.caption)
                    .font(.subheadline)
                    .foregroundStyle(captionColor(info.captionTone))
                    .lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
        .allowsHitTesting(interactive)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(info.accessibilityLabel)
        .accessibilityAddTraits(info.action == "none" ? [] : .isButton)
    }

    /// A portrait-sized mark where a shortcut shows who is paid.
    @ViewBuilder
    private var figureBadge: some View {
        switch tile.kind {
        case "ppq":
            Image("hub_ppq")
                .resizable()
                .scaledToFit()
                .padding(4)
                .frame(width: 26, height: 26)
                .background(.white, in: Circle())
        case "sats":
            Image(systemName: "bolt.fill")
                .font(.system(size: 13, weight: .bold))
                .frame(width: 26, height: 26)
                .background(HubPalette.bitcoin, in: Circle())
        default:
            Image(systemName: "bitcoinsign")
                .font(.system(size: 14, weight: .bold))
                .frame(width: 26, height: 26)
                .background(HubPalette.bitcoin, in: Circle())
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
                .background(.white.opacity(0.14), in: Capsule())
        } else {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .bold))
                .frame(width: 26, height: 26)
                .background(.white.opacity(0.14), in: Circle())
        }
    }

    private func captionColor(_ tone: String) -> Color {
        switch tone {
        case "up": return HubPalette.up
        case "down": return HubPalette.down
        case "accent": return .white
        default: return .white.opacity(0.8)
        }
    }

    private func shortcut(_ person: NativeHubPerson) -> some View {
        HubTileAction(interactive: interactive, action: { pay(person.actionId) }) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 7) {
                    HubAvatar(initials: person.contactInitials, size: 26)
                    Text(person.contactTitle)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 13, weight: .bold))
                        .frame(width: 26, height: 26)
                        .background(.white.opacity(0.14), in: Circle())
                        .accessibilityHidden(true)
                }
                Spacer(minLength: 0)
                Text(person.amount ?? "")
                    .font(.system(size: 36, weight: .bold, design: .rounded).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.45)
                Text(tile.title)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .allowsHitTesting(interactive)
        .accessibilityLabel([person.contactTitle, person.amount, tile.title].compactMap { $0 }.joined(separator: ", "))
    }

    /// One person, laid out like a shortcut: the portrait where the header is, the name where the
    /// figure is, and the address as its caption.
    private func single(_ person: NativeHubPerson) -> some View {
        let caption = tile.customTitle.flatMap { $0 == person.title ? nil : $0 }
            ?? (person.address.isEmpty || person.address == person.title ? tile.title : person.address)
        return HubTileAction(interactive: interactive, action: { pay(person.actionId) }) {
            VStack(alignment: .leading, spacing: 8) {
                HubAvatar(initials: person.initials, size: 44)
                Spacer(minLength: 0)
                Text(person.title)
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(caption)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .foregroundStyle(.white)
            .contentShape(Rectangle())
        }
        .allowsHitTesting(interactive)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(person.title)
        .accessibilityAddTraits(.isButton)
    }

    /// Several people under the tile's header. A few are listed line by line with what they were
    /// last paid; more than that stand side by side. Portraits have one size per tile height, so
    /// two collections next to each other match.
    private var group: some View {
        let card = tile.rows >= 2 && !compactPreview
        let people = compactPreview ? Array(tile.people.prefix(2)) : tile.people
        let listed = compactPreview || people.count <= (card ? 4 : 2)
        return VStack(alignment: .leading, spacing: 8) {
            peopleHeader
            if listed {
                // A wide row ends on the same line as the figures beside it; a card reads from the top.
                if !card { Spacer(minLength: 0) }
                VStack(spacing: card ? 12 : 8) {
                    ForEach(Array(people.enumerated()), id: \.element.id) { index, person in
                        personLine(person, avatarSize: card ? 44 : 36, seed: index)
                    }
                }
                if card { Spacer(minLength: 0) }
            } else {
                peopleGrid(people, columns: card ? 3 : 4, rows: card ? 2 : 1, portrait: card ? 64 : 48)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .accessibilityElement(children: .contain)
    }

    private func peopleGrid(_ people: [NativeHubPerson], columns: Int, rows: Int, portrait: CGFloat) -> some View {
        GeometryReader { proxy in
            let amounts = people.contains { $0.amount != nil }
            let cellWidth = (proxy.size.width - CGFloat(columns - 1) * 8) / CGFloat(columns)
            let cellHeight = proxy.size.height / CGFloat(rows)
            // The portrait keeps its size and only gives way when large text needs the room.
            let avatar = max(20, min(portrait, cellWidth, cellHeight - nameLine - (amounts ? amountLine : 0) - 4))
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .leading), count: columns),
                alignment: .leading,
                spacing: 0
            ) {
                ForEach(Array(people.prefix(columns * rows).enumerated()), id: \.element.id) { index, person in
                    personCell(person, avatarSize: avatar, amounts: amounts, seed: index)
                        .frame(height: cellHeight, alignment: .bottomLeading)
                }
            }
        }
    }

    private func personCell(_ person: NativeHubPerson, avatarSize: CGFloat, amounts: Bool, seed: Int) -> some View {
        personAction(person) {
            VStack(alignment: .leading, spacing: 6) {
                HubAvatar(initials: person.initials, size: avatarSize, seed: seed)
                VStack(alignment: .leading, spacing: 1) {
                    Text(person.title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    if amounts {
                        Text(person.amount ?? " ")
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.7))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.65)
                            .opacity(person.amount == nil ? 0 : 1)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
    }

    private func personLine(_ person: NativeHubPerson, avatarSize: CGFloat, seed: Int) -> some View {
        personAction(person) {
            HStack(spacing: 10) {
                HubAvatar(initials: person.initials, size: avatarSize, seed: seed)
                Text(person.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let amount = person.amount {
                    Text(amount)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.8))
                        .monospacedDigit()
                        .lineLimit(1)
                        .layoutPriority(1)
                }
            }
            .contentShape(Rectangle())
        }
    }

    private func personAction<Content: View>(
        _ person: NativeHubPerson,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        HubTileAction(interactive: interactive, action: {
            if !person.actionId.isEmpty { pay(person.actionId) }
        }, content: content)
            .allowsHitTesting(interactive)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(person.title + (person.amount.map { ", " + $0 } ?? ""))
            .accessibilityAddTraits(.isButton)
    }
}

/// The day's prices as one soft line that runs off both edges of the tile, fading out below.
private struct HubSparkline: View {
    let points: [CGFloat]

    var body: some View {
        GeometryReader { proxy in
            let line = line(in: proxy.size)
            ZStack {
                area(under: line, in: proxy.size)
                    .fill(
                        LinearGradient(
                            colors: [.white.opacity(0.14), .white.opacity(0)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                line.stroke(
                    .white.opacity(0.92),
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

/// Picks an amount; the wallet's own payment screen takes over once ppq.ai sent the invoice.
private struct HubPpqTopUpSheet: View {
    @ObservedObject var model: NativePaymentHubModel
    @Environment(\.dismiss) private var dismiss
    @State private var amount = "10"
    @State private var openedAt: Int32

    init(model: NativePaymentHubModel) {
        self.model = model
        _openedAt = State(initialValue: model.snapshot?.ppq.topUpSerial ?? 0)
    }

    var body: some View {
        NavigationStack {
            if let state = model.snapshot {
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
                                if let balance = state.ppq.balance {
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
                            ForEach(state.ppq.presets, id: \.self) { preset in
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
                        Text(state.ppq.topUpError ?? state.text.topUpBody)
                            .foregroundStyle(state.ppq.topUpError == nil ? Color.secondary : Color.red)
                    }
                }
                .disabled(state.ppq.toppingUp)
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .bottom) {
                    HubPrimaryButton(title: state.text.topUpAction, busy: state.ppq.toppingUp) {
                        model.controller.topUpPpq(amount: amount)
                    }
                }
                .navigationTitle(state.text.topUpTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel(state.text.cancel)
                            .disabled(state.ppq.toppingUp)
                    }
                }
                .onChange(of: amount) { _, _ in model.controller.clearPpqTopUpError() }
                .onChange(of: state.ppq.topUpSerial) { _, value in
                    if value != openedAt { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.snapshot?.ppq.toppingUp == true)
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
