import CoreImage
import Shared
import SwiftUI
import UIKit

/// The hero animation, drawn natively.
///
/// Kotlin owns the phase — which payment state the hero is in — and this owns everything about how
/// that phase looks and moves. Every value is derived from the time elapsed since the phase began,
/// so there is no animation state machine to fall out of sync, and a phase change interpolates
/// from whatever was on screen at that instant.
struct RaylHeroView: View {
    let phase: String
    /// Inputs turned away while the hero shows; each new one plays a short nudge, because the
    /// scanner has no viewfinder to show that it saw the code.
    var rejections = 0

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.appShellTabIsSelected) private var isTabSelected
    @State private var phaseStart = Date()
    @State private var fromFrame = HeroFrame()
    @State private var fromTint: HeroTint?
    @State private var nudgeStart: Date?
    @State private var settled = false

    private var heroPhase: HeroPhase { HeroPhase(phase) }

    /// Only Ready and Processing animate indefinitely; finite phases stop ticking once settled.
    private var isTimelinePaused: Bool {
        if !isTabSelected || scenePhase != .active { return true }
        switch heroPhase {
        case .ready, .processing: return false
        case .acknowledged, .succeeded, .failed: return settled
        }
    }

    var body: some View {
        animation
            .task(id: "\(phase)-\(rejections)") {
                settled = false
                switch heroPhase {
                case .ready, .processing:
                    return
                case .acknowledged, .succeeded, .failed:
                    // Longest finite phase (result pop + settle + color cross-fade) finishes within 1.1s.
                    try? await Task.sleep(for: .milliseconds(1_150))
                    if !Task.isCancelled {
                        settled = true
                    }
                }
            }
            .onChange(of: phase) { previous, _ in
                settled = false
                let elapsed = Date().timeIntervalSince(phaseStart)
                fromFrame = heroFrame(
                    phase: HeroPhase(previous),
                    elapsed: elapsed,
                    from: fromFrame
                )
                fromTint = tint(toward: targetTint(for: previous), elapsed: elapsed)
                phaseStart = Date()
            }
            .onChange(of: rejections) { _, _ in
                settled = false
                nudgeStart = Date()
            }
    }

    /// The canvas fills the whole hero area and the drawing is centred inside it. SwiftUI's
    /// `Canvas` clips to its bounds where Compose's does not, so sizing it to the drawing itself
    /// would cut the corner arcs off as they rotate.
    private var animation: some View {
        // Resolve the shared palette when the phase or environment changes, not on every tick.
        let target = targetTint(for: phase)
        let background = Color(uiColor: .systemBackground)
        return TimelineView(.animation(paused: isTimelinePaused)) { timeline in
            // Capture one complete frame during the view update. The drawing callback should
            // consume values, not read live SwiftUI state when Canvas chooses to redraw.
            let elapsed = timeline.date.timeIntervalSince(phaseStart)
            let frame = frame(at: timeline.date, elapsed: elapsed)
            let frameTint = tint(toward: target, elapsed: elapsed)

            Canvas(opaque: true) { context, size in
                // Both Scan and Recent use this background. Painting the entire surface keeps
                // its extent independent of the moving paths and avoids transparent compositing.
                // An opaque Canvas must fill its bounds before drawing any translucent bits.
                context.fill(
                    Path(CGRect(origin: .zero, size: size)),
                    with: .color(background)
                )
                let side = min(size.width * heroGeometry.canvasWidthFraction, size.height)
                context.translateBy(
                    x: (size.width - side) / 2,
                    y: (size.height - side) / 2
                )
                Self.draw(
                    frame,
                    tint: frameTint,
                    in: &context,
                    side: side
                )
            }
        }
    }

    private func frame(at date: Date, elapsed: Double) -> HeroFrame {
        let frame = heroFrame(phase: HeroPhase(phase), elapsed: elapsed, from: fromFrame)
        // Added after the phase frame and kept out of `fromFrame`, so a phase change never
        // captures half a nudge and the nudge never interrupts the phase.
        guard let nudgeStart else { return frame }
        return nudged(frame, elapsed: date.timeIntervalSince(nudgeStart))
    }

    private func targetTint(for phaseValue: String) -> HeroTint {
        let palette = NativeHeroPaletteKt.nativeHeroPalette(phaseValue: phaseValue)
        return HeroTint(argb: colorScheme == .dark ? palette.darkArgb : palette.lightArgb)
    }

    /// Phase colours cross-fade over half a second rather than snapping, matching the original.
    private func tint(toward target: HeroTint, elapsed: Double) -> HeroTint {
        guard let fromTint else { return target }
        return HeroTint.blend(fromTint, target, easeInOutCubic(elapsed / 0.5))
    }

    private static func draw(
        _ frame: HeroFrame,
        tint heroTint: HeroTint,
        in context: inout GraphicsContext,
        side: CGFloat
    ) {
        let center = CGPoint(x: side / 2, y: side / 2)
        let tint = heroTint.color

        // Context copies isolate transforms without allocating nested transparency layers.
        // These paths need no group effects; draw them directly into the same Canvas layer.
        var cluster = context
        cluster.translateBy(x: center.x, y: center.y)
        cluster.scaleBy(x: frame.clusterScale, y: frame.clusterScale)
        cluster.translateBy(x: -center.x, y: -center.y)
        cluster.translateBy(x: frame.shakeX, y: 0)

        for (index, spec) in heroGeometry.squares.enumerated() {
            drawSquare(spec, index: index, frame: frame, in: &cluster, side: side, tint: tint)
        }

        if frame.boltScale > 0 {
            drawBolt(frame, in: &cluster, side: side, center: center, tint: tint)
        }

        var ring = context
        ring.translateBy(x: center.x, y: center.y)
        ring.rotate(by: .degrees(frame.rotation))
        ring.translateBy(x: -center.x, y: -center.y)

        for (index, spec) in heroGeometry.arcs.enumerated() {
            let offset = frame.arcOffsets[index]
            let length = side * spec.cornerLength
            let rect = CGRect(
                x: (spec.x + offset.width) * side,
                y: (spec.y + offset.height) * side,
                width: length,
                height: length
            )
            // Trimming the inscribed ellipse gives the same corner bracket as Compose's
            // `drawArc`, without depending on how `addArc` interprets sweep direction in a
            // flipped coordinate space. The ellipse path starts at 0° and runs clockwise, so
            // the angles map straight onto trim fractions.
            let path = Path(ellipseIn: rect).trimmedPath(
                from: spec.startAngle / 360,
                to: (spec.startAngle + spec.sweepAngle) / 360
            )
            ring.stroke(
                path,
                with: .color(tint),
                style: StrokeStyle(
                    lineWidth: side * heroGeometry.arcStrokeWidthFraction,
                    lineCap: .round
                )
            )
        }
    }

    private static func drawSquare(
        _ spec: HeroSquareSpec,
        index: Int,
        frame: HeroFrame,
        in context: inout GraphicsContext,
        side: CGFloat,
        tint: Color
    ) {
        let offset = frame.squareOffsets[index]
        let originX = (spec.x + offset.width) * side
        let originY = (spec.y + offset.height) * side
        let size = spec.size * side
        let squareCenter = CGPoint(x: originX + size / 2, y: originY + size / 2)
        let scale = frame.squareScales[index]
        guard scale > 0 else { return }

        var square = context
        square.translateBy(x: squareCenter.x, y: squareCenter.y)
        square.scaleBy(x: scale, y: scale)
        square.translateBy(x: -squareCenter.x, y: -squareCenter.y)

        if spec.outlined {
            let rect = CGRect(x: originX, y: originY, width: size, height: size)
            square.stroke(
                Path(
                    roundedRect: rect,
                    cornerSize: CGSize(
                        width: size * heroGeometry.squareCornerRadiusFraction,
                        height: size * heroGeometry.squareCornerRadiusFraction
                    )
                ),
                with: .color(tint),
                lineWidth: size * heroGeometry.squareStrokeWidthFraction
            )
            let child = size * heroGeometry.finderInnerSizeFraction
            let childRect = CGRect(
                x: originX + (size - child) / 2,
                y: originY + (size - child) / 2,
                width: child,
                height: child
            )
            square.fill(
                Path(
                    roundedRect: childRect,
                    cornerSize: CGSize(
                        width: child * heroGeometry.finderInnerCornerRadiusFraction,
                        height: child * heroGeometry.finderInnerCornerRadiusFraction
                    )
                ),
                with: .color(tint)
            )
        } else {
            let gap = size * heroGeometry.dataBitGapFraction
            let mini = (size - gap) / 2
            let corner = CGSize(
                width: mini * heroGeometry.dataBitCornerRadiusFraction,
                height: mini * heroGeometry.dataBitCornerRadiusFraction
            )
            let origins = [
                CGPoint(x: originX, y: originY),
                CGPoint(x: originX + mini + gap, y: originY),
                CGPoint(x: originX, y: originY + mini + gap),
                CGPoint(x: originX + mini + gap, y: originY + mini + gap)
            ]
            for (bit, point) in origins.enumerated() {
                let rect = CGRect(x: point.x, y: point.y, width: mini, height: mini)
                square.fill(
                    Path(roundedRect: rect, cornerSize: corner),
                    with: .color(tint.opacity(frame.bitOpacities[bit]))
                )
            }
        }
    }

    private static func drawBolt(
        _ frame: HeroFrame,
        in context: inout GraphicsContext,
        side: CGFloat,
        center: CGPoint,
        tint: Color
    ) {
        let bolt = side * heroGeometry.boltSizeFraction
        var path = Path()
        guard let first = heroGeometry.bolt.first else { return }
        path.move(to: CGPoint(x: bolt * first.x, y: bolt * first.y))
        for point in heroGeometry.bolt.dropFirst() {
            path.addLine(to: CGPoint(x: bolt * point.x, y: bolt * point.y))
        }
        path.closeSubpath()

        let bounds = path.boundingRect
        let pathCenter = CGPoint(x: bounds.midX, y: bounds.midY)

        var layer = context
        layer.translateBy(x: center.x - pathCenter.x, y: center.y - pathCenter.y)
        layer.translateBy(x: pathCenter.x, y: pathCenter.y)
        layer.scaleBy(x: frame.boltScale, y: frame.boltScale)
        layer.translateBy(x: -pathCenter.x, y: -pathCenter.y)
        layer.fill(path, with: .color(tint))
    }
}

// MARK: - Geometry

private struct HeroSquareSpec {
    let x: CGFloat
    let y: CGFloat
    let size: CGFloat
    let outlined: Bool
}

private struct HeroArcSpec {
    let x: CGFloat
    let y: CGFloat
    let startAngle: Double
    let sweepAngle: Double
    let cornerLength: CGFloat
}

private struct HeroPoint {
    let x: CGFloat
    let y: CGFloat
}

private struct HeroGeometrySpec {
    let canvasWidthFraction: CGFloat
    let squareCornerRadiusFraction: CGFloat
    let squareStrokeWidthFraction: CGFloat
    let finderInnerSizeFraction: CGFloat
    let finderInnerCornerRadiusFraction: CGFloat
    let dataBitGapFraction: CGFloat
    let dataBitCornerRadiusFraction: CGFloat
    let dataBitCount: Int
    let arcStrokeWidthFraction: CGFloat
    let boltSizeFraction: CGFloat
    let squareCompressionFraction: CGFloat
    let arcCompressionFraction: CGFloat
    let arcPopFraction: CGFloat
    let loadingArcInsetFraction: CGFloat
    let squares: [HeroSquareSpec]
    let arcs: [HeroArcSpec]
    let bolt: [HeroPoint]

    init(_ shared: NativeHeroGeometry) {
        canvasWidthFraction = CGFloat(shared.canvasWidthFraction)
        squareCornerRadiusFraction = CGFloat(shared.squareCornerRadiusFraction)
        squareStrokeWidthFraction = CGFloat(shared.squareStrokeWidthFraction)
        finderInnerSizeFraction = CGFloat(shared.finderInnerSizeFraction)
        finderInnerCornerRadiusFraction = CGFloat(shared.finderInnerCornerRadiusFraction)
        dataBitGapFraction = CGFloat(shared.dataBitGapFraction)
        dataBitCornerRadiusFraction = CGFloat(shared.dataBitCornerRadiusFraction)
        dataBitCount = Int(shared.dataBitCount)
        arcStrokeWidthFraction = CGFloat(shared.arcStrokeWidthFraction)
        boltSizeFraction = CGFloat(shared.boltSizeFraction)
        squareCompressionFraction = CGFloat(shared.squareCompressionFraction)
        arcCompressionFraction = CGFloat(shared.arcCompressionFraction)
        arcPopFraction = CGFloat(shared.arcPopFraction)
        loadingArcInsetFraction = CGFloat(shared.loadingArcInsetFraction)
        squares = shared.squares.map {
            HeroSquareSpec(
                x: CGFloat($0.x),
                y: CGFloat($0.y),
                size: CGFloat($0.size),
                outlined: $0.outlined
            )
        }
        arcs = shared.arcs.map {
            HeroArcSpec(
                x: CGFloat($0.x),
                y: CGFloat($0.y),
                startAngle: Double($0.startAngle),
                sweepAngle: Double($0.sweepAngle),
                cornerLength: CGFloat($0.length)
            )
        }
        bolt = shared.bolt.map { HeroPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
    }
}

/// Kotlin is consulted once for the immutable spec; animation frames remain entirely native.
private let heroGeometry = HeroGeometrySpec(NativeHeroGeometryKt.nativeHeroGeometry())

/// Fixed stand-ins for the flicker's random cycle lengths, so the bits stay out of step with each
/// other without the animation depending on a random seed.
private let bitFlickerPeriods: [Double] = [0.52, 0.74, 0.61, 0.88]

private func stepTowardCenter(_ value: CGFloat, _ size: CGFloat, _ step: CGFloat) -> CGFloat {
    (0.5 - (value + size / 2)) * step
}

// MARK: - Frame

enum HeroPhase {
    case ready
    case acknowledged
    case processing
    case succeeded
    case failed

    init(_ value: String) {
        switch value {
        case "acknowledged": self = .acknowledged
        case "processing": self = .processing
        case "succeeded": self = .succeeded
        case "failed": self = .failed
        default: self = .ready
        }
    }
}

struct HeroFrame {
    var clusterScale: CGFloat = 1
    var shakeX: CGFloat = 0
    var rotation: Double = 0
    var boltScale: CGFloat = 0
    var squareScales = Array(repeating: CGFloat(1), count: heroGeometry.squares.count)
    var squareOffsets = Array(repeating: CGSize.zero, count: heroGeometry.squares.count)
    var arcOffsets = Array(repeating: CGSize.zero, count: heroGeometry.arcs.count)
    var bitOpacities = Array(repeating: Double(1), count: heroGeometry.dataBitCount)
}

private func heroFrame(phase: HeroPhase, elapsed: Double, from: HeroFrame) -> HeroFrame {
    switch phase {
    case .ready: return readyFrame(elapsed: elapsed, from: from)
    case .acknowledged: return compressedFrame(elapsed: elapsed, from: from, loading: false)
    case .processing: return compressedFrame(elapsed: elapsed, from: from, loading: true)
    case .succeeded: return resultFrame(elapsed: elapsed, from: from, isError: false)
    case .failed: return resultFrame(elapsed: elapsed, from: from, isError: true)
    }
}

/// Scanning: the ring drifts, the finder squares pulse in sequence, the data bits flicker.
private func readyFrame(elapsed: Double, from: HeroFrame) -> HeroFrame {
    var frame = HeroFrame()
    let settle = easeInOutCubic(elapsed / 0.5)

    frame.clusterScale = lerp(from.clusterScale, 1, settle)
    frame.rotation = keyframe(
        elapsed.truncatingRemainder(dividingBy: 4),
        [(0, 0), (1, 10), (2, 0), (3, -10), (4, 0)],
        eased: true
    )

    let pulseIn = easeOutCubic(elapsed / 0.5)
    for index in heroGeometry.squares.indices {
        let start = Double(index) * 0.1
        let pulse = keyframe(
            elapsed.truncatingRemainder(dividingBy: 1),
            [(0, 1), (start, 1), (start + 0.15, 1.15), (start + 0.3, 1), (1, 1)],
            eased: true
        )
        frame.squareScales[index] = lerp(from.squareScales[index], pulse, pulseIn)
        frame.squareOffsets[index] = lerp(from.squareOffsets[index], .zero, settle)
        frame.arcOffsets[index] = lerp(
            from.arcOffsets[index],
            .zero,
            easeInOutCubic(elapsed / 0.25)
        )

        let period = bitFlickerPeriods[index]
        let cycle = elapsed.truncatingRemainder(dividingBy: period * 2)
        let time = cycle <= period ? cycle : period * 2 - cycle
        frame.bitOpacities[index] = keyframe(
            time,
            [
                (0, 1),
                (period * 0.2, 0.3),
                (period * 0.4, 1),
                (period * 0.7, 0.6),
                (period, 1)
            ],
            eased: false
        )
    }
    return frame
}

/// Acknowledged and Processing: the cluster clenches inward. Processing then pulses the corners.
private func compressedFrame(elapsed: Double, from: HeroFrame, loading: Bool) -> HeroFrame {
    var frame = HeroFrame()
    frame.rotation = lerp(from.rotation, 0, easeInOutCubic(elapsed / 0.3))
    frame.clusterScale = lerp(from.clusterScale, 0.9, easeInOutCubic(elapsed / 0.3))
    frame.shakeX = from.shakeX
    frame.boltScale = from.boltScale
    frame.squareScales = from.squareScales

    let compress = easeInOutCubic(elapsed / 0.5)
    for index in heroGeometry.squares.indices {
        let square = heroGeometry.squares[index]
        frame.squareOffsets[index] = lerp(
            from.squareOffsets[index],
            CGSize(
                width: stepTowardCenter(
                    square.x,
                    square.size,
                    heroGeometry.squareCompressionFraction
                ),
                height: stepTowardCenter(
                    square.y,
                    square.size,
                    heroGeometry.squareCompressionFraction
                )
            ),
            compress
        )

        let arc = heroGeometry.arcs[index]
        let compressed = CGSize(
            width: stepTowardCenter(
                arc.x,
                arc.cornerLength,
                heroGeometry.arcCompressionFraction
            ),
            height: stepTowardCenter(
                arc.y,
                arc.cornerLength,
                heroGeometry.arcCompressionFraction
            )
        )
        let settled = lerp(from.arcOffsets[index], compressed, compress)

        let pulseStart = 0.5 + Double(index) * 0.1
        if loading, elapsed > pulseStart {
            let inner = CGSize(
                width: compressed.width * heroGeometry.loadingArcInsetFraction,
                height: compressed.height * heroGeometry.loadingArcInsetFraction
            )
            let progress = keyframe(
                (elapsed - pulseStart).truncatingRemainder(dividingBy: 0.6),
                [(0, 0), (0.3, 1), (0.6, 0)],
                eased: true
            )
            frame.arcOffsets[index] = lerp(compressed, inner, progress)
        } else {
            frame.arcOffsets[index] = settled
        }
    }
    return frame
}

/// Succeeded and Failed: the corners squeeze, spring back out, and settle. Success replaces the
/// squares with the bolt; failure keeps them and shakes the cluster.
private func resultFrame(elapsed: Double, from: HeroFrame, isError: Bool) -> HeroFrame {
    var frame = HeroFrame()
    frame.rotation = lerp(from.rotation, 0, easeInOutCubic(elapsed / 0.3))

    for index in heroGeometry.arcs.indices {
        let arc = heroGeometry.arcs[index]
        let compressed = CGSize(
            width: stepTowardCenter(
                arc.x,
                arc.cornerLength,
                heroGeometry.arcCompressionFraction
            ),
            height: stepTowardCenter(
                arc.y,
                arc.cornerLength,
                heroGeometry.arcCompressionFraction
            )
        )
        let popped = CGSize(
            width: stepTowardCenter(arc.x, arc.cornerLength, heroGeometry.arcPopFraction),
            height: stepTowardCenter(arc.y, arc.cornerLength, heroGeometry.arcPopFraction)
        )
        if elapsed <= 0.5 {
            frame.arcOffsets[index] = lerp(
                from.arcOffsets[index],
                compressed,
                easeInOutCubic(elapsed / 0.5)
            )
        } else if elapsed <= 0.75 {
            frame.arcOffsets[index] = lerp(
                compressed,
                popped,
                easeInOutCubic((elapsed - 0.5) / 0.25)
            )
        } else {
            frame.arcOffsets[index] = lerp(popped, .zero, easeInOutCubic((elapsed - 0.75) / 0.25))
        }
    }

    // The cluster only resets once the corners have finished squeezing.
    let reset = elapsed - 0.5
    if reset <= 0 {
        frame.clusterScale = from.clusterScale
        frame.squareScales = from.squareScales
        frame.squareOffsets = from.squareOffsets
    } else {
        frame.clusterScale = lerp(from.clusterScale, 1, easeInOutCubic(reset / 0.5))
        let squareTarget: CGFloat = isError ? 1 : 0
        let squareProgress = easeInOutCubic(reset / 0.2)
        for index in heroGeometry.squares.indices {
            frame.squareScales[index] = lerp(
                from.squareScales[index],
                squareTarget,
                squareProgress
            )
            frame.squareOffsets[index] = lerp(
                from.squareOffsets[index],
                .zero,
                easeInOutCubic(reset / 0.5)
            )
        }
    }

    if isError {
        frame.shakeX = keyframe(
            elapsed,
            [
                (0, 0), (0.05, -10), (0.1, 10), (0.15, -10),
                (0.2, 10), (0.25, -5), (0.3, 5), (0.5, 0)
            ],
            eased: false
        )
    } else if elapsed > 0.15 {
        let pop = elapsed - 0.15
        frame.boltScale =
            pop <= 0.25
                ? lerp(0, 1.2, easeInOutCubic(pop / 0.25))
                : lerp(1.2, 1, easeInOutCubic((pop - 0.25) / 0.15))
    }
    return frame
}

/// An input was turned away: the corners close in on the code, then let go with a shake.
private func nudged(_ frame: HeroFrame, elapsed: Double) -> HeroFrame {
    guard elapsed >= 0, elapsed < 0.6 else { return frame }
    var frame = frame
    let closing = CGFloat(
        elapsed <= 0.15
            ? easeInOutCubic(elapsed / 0.15)
            : 1 - easeInOutCubic((elapsed - 0.25) / 0.35)
    )
    for index in heroGeometry.arcs.indices {
        let arc = heroGeometry.arcs[index]
        frame.arcOffsets[index].width +=
            stepTowardCenter(arc.x, arc.cornerLength, heroGeometry.arcCompressionFraction)
            * closing
        frame.arcOffsets[index].height +=
            stepTowardCenter(arc.y, arc.cornerLength, heroGeometry.arcCompressionFraction)
            * closing
    }
    frame.shakeX += keyframe(
        elapsed,
        [(0, 0), (0.2, 0), (0.25, -6), (0.3, 6), (0.35, -4), (0.4, 4), (0.5, 0)],
        eased: false
    )
    return frame
}

// MARK: - Interpolation

private func easeInOutCubic(_ value: Double) -> Double {
    let t = min(max(value, 0), 1)
    return t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
}

private func easeOutCubic(_ value: Double) -> Double {
    let t = min(max(value, 0), 1)
    return 1 - pow(1 - t, 3)
}

private func lerp(_ from: Double, _ to: Double, _ progress: Double) -> Double {
    from + (to - from) * min(max(progress, 0), 1)
}

private func lerp(_ from: CGFloat, _ to: CGFloat, _ progress: Double) -> CGFloat {
    CGFloat(lerp(Double(from), Double(to), progress))
}

private func lerp(_ from: CGSize, _ to: CGSize, _ progress: Double) -> CGSize {
    CGSize(
        width: lerp(from.width, to.width, progress),
        height: lerp(from.height, to.height, progress)
    )
}

/// Value of a keyframe track at [time], optionally easing each segment the way Compose does.
private func keyframe(_ time: Double, _ points: [(Double, Double)], eased: Bool) -> Double {
    guard let first = points.first else { return 0 }
    if time <= first.0 { return first.1 }
    for index in 1..<points.count {
        let start = points[index - 1]
        let end = points[index]
        guard time <= end.0 else { continue }
        let span = end.0 - start.0
        guard span > 0 else { return end.1 }
        let progress = (time - start.0) / span
        return lerp(start.1, end.1, eased ? easeInOutCubic(progress) : progress)
    }
    return points[points.count - 1].1
}

// MARK: - Receipt

struct NativePaymentPreimageQrCode: View {
    private static let ciContext = CIContext()

    let data: String
    @State private var image: UIImage?

    init(data: String) {
        self.data = data
        _image = State(initialValue: Self.qrImage(from: data))
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(Color.white)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .padding(16)
                }
            }
            .onChange(of: data) { _, newValue in
                image = Self.qrImage(from: newValue)
            }
    }

    private static func qrImage(from value: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cgImage = ciContext.createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}

/// A hero colour held as components so one phase's colour can cross-fade into the next.
struct HeroTint {
    var red: Double
    var green: Double
    var blue: Double
    var opacity: Double

    init(argb: Int64) {
        let value = UInt32(truncatingIfNeeded: argb)
        red = Double((value >> 16) & 0xFF) / 255
        green = Double((value >> 8) & 0xFF) / 255
        blue = Double(value & 0xFF) / 255
        opacity = Double((value >> 24) & 0xFF) / 255
    }

    private init(red: Double, green: Double, blue: Double, opacity: Double) {
        self.red = red
        self.green = green
        self.blue = blue
        self.opacity = opacity
    }

    var color: Color {
        Color(.sRGB, red: red, green: green, blue: blue, opacity: opacity)
    }

    static func blend(_ from: HeroTint, _ to: HeroTint, _ progress: Double) -> HeroTint {
        HeroTint(
            red: lerp(from.red, to.red, progress),
            green: lerp(from.green, to.green, progress),
            blue: lerp(from.blue, to.blue, progress),
            opacity: lerp(from.opacity, to.opacity, progress)
        )
    }
}

#Preview("Hero phases") {
    HeroPhasePreview()
}

/// Flips the hero through every phase so its motion can be compared against the Android build.
private struct HeroPhasePreview: View {
    private static let phases = [
        "ready", "acknowledged", "processing", "succeeded", "failed"
    ]

    @State private var phase = "ready"
    @State private var rejections = 0

    var body: some View {
        VStack(spacing: 24) {
            RaylHeroView(phase: phase, rejections: rejections)
                .frame(height: 360)

            Picker("Phase", selection: $phase) {
                ForEach(Self.phases, id: \.self) { value in
                    Text(value.capitalized).tag(value)
                }
            }
            .pickerStyle(.segmented)

            Button("Reject input") { rejections += 1 }

            Spacer()
        }
        .padding()
    }
}
