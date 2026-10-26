import ReflectionCore
import SwiftUI

struct PressureEditor: View {
    @Binding var mapping: MappingConfiguration
    let livePressure: Double
    let hasLiveSample: Bool

    private var points: [PressurePoint] {
        if !mapping.pressureCurve.isEmpty { return mapping.pressureCurve }
        return [0.0, 0.25, 0.5, 0.75, 1.0].map {
            PressurePoint(input: $0, output: safeOutput($0))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Pressure response").font(.headline)
                Spacer()
                Menu("Presets") {
                    Button("Linear") { mapping.pressureCurve = []; mapping.pressureGamma = 1 }
                    Button("Soft") { setPreset([0, 0.45, 0.72, 0.9, 1]) }
                    Button("Firm") { setPreset([0, 0.1, 0.28, 0.58, 1]) }
                }
                .fixedSize()
            }
            Picker("Curve type", selection: Binding(
                get: { !mapping.pressureCurve.isEmpty },
                set: { custom in
                    mapping.pressureCurve = custom ? points : []
                })) {
                    Text("Gamma").tag(false)
                    Text("Custom points").tag(true)
                }
                .pickerStyle(.segmented)

            if mapping.pressureCurve.isEmpty {
                HStack {
                    Text("Gamma").frame(width: 70, alignment: .leading)
                    Slider(value: $mapping.pressureGamma, in: 0.1...4)
                    DecimalField("Gamma", value: $mapping.pressureGamma)
                        .frame(width: 84)
                }
                Text("Below 1 reaches stronger pressure sooner. Above 1 needs a firmer touch.")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                Text("Drag the points or enter precise values. Outputs increase smoothly along each segment.")
                    .font(.caption).foregroundColor(.secondary)
            }

            HStack(spacing: 12) {
                Text("OUTPUT").font(.system(size: 9, weight: .medium, design: .monospaced))
                    .foregroundColor(.secondary).fixedSize().rotationEffect(.degrees(-90))
                    .frame(width: 16)
                VStack(spacing: 8) {
                    graph.frame(height: 240)
                    HStack {
                        Text("0")
                        Spacer()
                        Text("INPUT PRESSURE")
                        Spacer()
                        Text("1")
                    }
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(.secondary)
                }
            }
            if hasLiveSample {
                HStack {
                    Circle().fill(Color.accentColor).frame(width: 7, height: 7)
                    Text("Live input on draft curve")
                    Spacer()
                    Text("\(livePressure, specifier: "%.3f")  >  \(safeOutput(livePressure), specifier: "%.3f")")
                        .monospacedDigit()
                }
                .font(.caption).foregroundColor(.secondary)
            }
            if !mapping.pressureCurve.isEmpty { pointEditor }
        }
    }

    private var graph: some View {
        GeometryReader { geometry in
            let inset: CGFloat = 10
            let size = CGSize(width: max(1, geometry.size.width - inset * 2),
                              height: max(1, geometry.size.height - inset * 2))
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color(nsColor: .textBackgroundColor))
                Path { path in
                    for index in 0...4 {
                        let t = CGFloat(index) / 4
                        path.move(to: CGPoint(x: inset + size.width * t, y: inset))
                        path.addLine(to: CGPoint(x: inset + size.width * t, y: inset + size.height))
                        path.move(to: CGPoint(x: inset, y: inset + size.height * t))
                        path.addLine(to: CGPoint(x: inset + size.width, y: inset + size.height * t))
                    }
                }.stroke(Color.secondary.opacity(0.15), lineWidth: 1)
                Path { path in
                    path.move(to: CGPoint(x: inset, y: inset + size.height))
                    path.addLine(to: CGPoint(x: inset + size.width, y: inset))
                }.stroke(Color.secondary.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                Path { path in
                    for step in 0...256 {
                        let input = Double(step) / 256
                        let p = CGPoint(x: inset + size.width * input,
                                        y: inset + size.height * (1 - safeOutput(input)))
                        if step == 0 { path.move(to: p) } else { path.addLine(to: p) }
                    }
                }.stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))

                ForEach(Array(points.enumerated()), id: \.offset) { index, point in
                    let isEndpoint = index == 0 || index == points.count - 1
                    Circle()
                        .fill(Color(nsColor: .textBackgroundColor))
                        .overlay(Circle().stroke(Color.accentColor, lineWidth: 2))
                        .frame(width: isEndpoint ? 9 : 14, height: isEndpoint ? 9 : 14)
                        .frame(width: 24, height: 24)
                        .contentShape(Circle())
                        .position(x: inset + size.width * point.input,
                                  y: inset + size.height * (1 - point.output))
                        .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("pressure-graph"))
                            .onChanged { drag in
                                guard !isEndpoint else { return }
                                updatePoint(index, input: (drag.location.x - inset) / size.width,
                                            output: 1 - (drag.location.y - inset) / size.height)
                            })
                        .accessibilityLabel(isEndpoint ? "Fixed curve endpoint" : "Pressure point \(index)")
                }
                if hasLiveSample {
                    Circle().fill(Color.accentColor.opacity(0.22))
                        .overlay(Circle().stroke(Color.accentColor, lineWidth: 1))
                        .frame(width: 20, height: 20)
                        .position(x: inset + size.width * min(1, max(0, livePressure)),
                                  y: inset + size.height * (1 - safeOutput(livePressure)))
                        .allowsHitTesting(false)
                }
            }
            .coordinateSpace(name: "pressure-graph")
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Pressure response graph")
    }

    private var pointEditor: some View {
        VStack(spacing: 8) {
            HStack {
                Text("Point").frame(width: 52, alignment: .leading)
                Text("Input").frame(maxWidth: .infinity, alignment: .leading)
                Text("Output").frame(maxWidth: .infinity, alignment: .leading)
                Spacer().frame(width: 25)
            }
            .font(.caption).foregroundColor(.secondary)
            ForEach(mapping.pressureCurve.indices, id: \.self) { index in
                HStack {
                    Text("\(index + 1)").font(.caption.monospacedDigit())
                        .frame(width: 52, alignment: .leading)
                    DecimalField("Input point \(index + 1)", value: pointBinding(index, input: true))
                    DecimalField("Output point \(index + 1)", value: pointBinding(index, input: false))
                    Button { mapping.pressureCurve.remove(at: index) } label: {
                        Image(systemName: "minus.circle")
                    }
                    .buttonStyle(.borderless)
                    .frame(width: 25)
                    .help("Remove point")
                }
                .disabled(index == 0 || index == mapping.pressureCurve.count - 1)
            }
            HStack {
                Button("Add point") { addPoint() }.disabled(mapping.pressureCurve.count >= 32)
                Spacer()
                Text("\(mapping.pressureCurve.count) / 32 points")
                    .font(.caption).foregroundColor(.secondary)
            }
        }
    }

    private func safeOutput(_ input: Double) -> Double {
        let result = mapping.mapPressure(input)
        return result.isFinite ? min(1, max(0, result)) : 0
    }

    private func setPreset(_ output: [Double]) {
        mapping.pressureCurve = output.enumerated().map {
            PressurePoint(input: Double($0.offset) / Double(output.count - 1), output: $0.element)
        }
    }

    private func pointBinding(_ index: Int, input: Bool) -> Binding<Double> {
        Binding(get: {
            guard mapping.pressureCurve.indices.contains(index) else { return 0 }
            return input ? mapping.pressureCurve[index].input : mapping.pressureCurve[index].output
        }, set: { value in
            guard mapping.pressureCurve.indices.contains(index) else { return }
            let point = mapping.pressureCurve[index]
            updatePoint(index, input: input ? value : point.input, output: input ? point.output : value)
        })
    }

    private func updatePoint(_ index: Int, input: Double, output: Double) {
        var updated = points
        guard index > 0, index < updated.count - 1, input.isFinite, output.isFinite else { return }
        let left = updated[index - 1], right = updated[index + 1]
        let spacing = min(0.001, (right.input - left.input) / 4)
        updated[index] = PressurePoint(
            input: min(right.input - spacing, max(left.input + spacing, input)),
            output: min(right.output, max(left.output, output)))
        mapping.pressureCurve = updated
    }

    private func addPoint() {
        let current = points
        guard current.count < 32 else { return }
        let index = (1..<current.count).max {
            current[$0].input - current[$0 - 1].input < current[$1].input - current[$1 - 1].input
        } ?? 1
        let input = (current[index - 1].input + current[index].input) / 2
        var updated = current
        updated.insert(PressurePoint(input: input, output: safeOutput(input)), at: index)
        mapping.pressureCurve = updated
    }
}

struct DecimalField: View {
    let title: String
    @Binding var value: Double

    init(_ title: String, value: Binding<Double>) {
        self.title = title
        _value = value
    }

    var body: some View {
        TextField(title, value: $value, format: .number.precision(.fractionLength(0...4)))
            .textFieldStyle(.roundedBorder)
            .multilineTextAlignment(.trailing)
            .accessibilityLabel(title)
    }
}
