import qupath.lib.common.GeneralTools

// ============================================================
// ONE-CLICK NUCLEUS CLASSIFICATION
//
// Exports detection measurements, runs the pooled Python model,
// and applies the predicted classes back onto the detections.
//
// SETUP (edit the three paths below to match this machine), then:
//   Run          -> classifies the currently open image
//   Run for project -> classifies every image
//
// Predictions are written as classifications plus a "pred_confidence"
// measurement on each detection, so low-confidence cells can be found
// afterwards (Measure > Show detection measurements, sort by that column).
// ============================================================

// ---------------- SETTINGS: EDIT THESE ----------------
def PYTHON_EXE   = "/home/gilbert/anaconda3/bin/python"
def PREDICT_PY   = "/mnt/NAS/QuPath_Projects_AA/cellpose-dino-cls/MLP_ANN/predict_nuclei.py"
def MODEL_PATH   = "/mnt/NAS/QuPath_Projects_AA/cellpose-dino-cls/MLP_ANN/pooled_model.pt"
import qupath.lib.common.GeneralTools



double MIN_CONFIDENCE = 0.0   // e.g. 0.5 leaves uncertain nuclei unclassified
boolean BACKUP_EXISTING_CLASSES = true   // save current classes to "orig_class_id"
int TIMEOUT_MINUTES = 30

// ---------------- MEASUREMENT NAMES (must match the export script) ----------------
def SHAPE = ["Area µm^2", "Length µm", "Circularity", "Solidity",
             "Max diameter µm", "Min diameter µm"]
def SQ = "Square: Diameter 25.0 µm: 2.00 µm per pixel: "
def INTENSITY = [
    SQ + "Hematoxylin: Mean", SQ + "Hematoxylin: Std.dev.", SQ + "Hematoxylin: Min",
    SQ + "Hematoxylin: Max",  SQ + "Hematoxylin: Median",
    SQ + "DAB: Mean",         SQ + "DAB: Std.dev.",         SQ + "DAB: Min",
    SQ + "DAB: Max",          SQ + "DAB: Median"
]
def baseFeatures = SHAPE + INTENSITY
def allFeatures = baseFeatures +
        baseFeatures.collect { "Smoothed: 50 µm: " + it } + ["Smoothed: 50 µm: Nearby detection counts"] +
        baseFeatures.collect { "Smoothed: 75 µm: " + it } + ["Smoothed: 75 µm: Nearby detection counts"]

def shortName = { String m ->
    String s = m
    s = s.replace(SQ, "sq25_")
    s = s.replace("Smoothed: 50 µm: ", "sm50_").replace("Smoothed: 75 µm: ", "sm75_")
    s = s.replace("Area µm^2", "area_um2").replace("Length µm", "length_um")
    s = s.replace("Max diameter µm", "max_diam_um").replace("Min diameter µm", "min_diam_um")
    s = s.replace("Nearby detection counts", "nearby_count")
    s = s.replace("Hematoxylin", "hem").replace("DAB", "dab")
    s = s.replace("Std.dev.", "std").replace("Std.Dev.", "std")
    s = s.replace("Circularity", "circularity").replace("Solidity", "solidity")
    s = s.replace("Mean", "mean").replace("Median", "median")
    s = s.replace("Min", "min").replace("Max", "max")
    s = s.replace(": ", "_").replace(":", "_").replace(" ", "_")
    while (s.contains("__")) s = s.replace("__", "_")
    return s.toLowerCase()
}

// ---------------- CHECKS ----------------
def imageData = getCurrentImageData()
if (imageData == null) { print "No image open!"; return }
def server = imageData.getServer()
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())
print "=== " + name + " ==="

[[PYTHON_EXE, "Python"], [PREDICT_PY, "predict_nuclei.py"], [MODEL_PATH, "model"]].each { p, label ->
    if (!new File(p).exists()) {
        print "ERROR: " + label + " not found at: " + p
        print "Edit the paths at the top of this script."
        throw new RuntimeException(label + " not found")
    }
}

def detections = getDetectionObjects()
if (detections.isEmpty()) { print "No detections - run segmentation first."; return }
print "Detections: " + detections.size()

def getMeasure = { det, String mname ->
    try { return det.getMeasurementList().get(mname) } catch (Exception e) { return Double.NaN }
}

// Fail early if features were never computed - otherwise Python receives a
// CSV of empty columns and the predictions would be meaningless.
def sample = detections.take(100)
def missing = allFeatures.findAll { m ->
    sample.every { d -> def v = getMeasure(d, m); v == null || Double.isNaN(v) }
}
if (!missing.isEmpty()) {
    print "ERROR: " + missing.size() + "/" + allFeatures.size() + " features are missing on this image."
    print "Run the feature-computation script first (intensity + smoothed features)."
    missing.take(5).each { print "   missing: " + it }
    return
}

// ---------------- 1. EXPORT CSV ----------------
def tmpDir = new File(System.getProperty("java.io.tmpdir"), "qupath_nuclei_predict")
tmpDir.mkdirs()
def csvFile = new File(tmpDir, name + "_features.csv")
def jsonFile = new File(tmpDir, name + "_predictions.json")

def fmt = { v -> (v == null || (v instanceof Double && v.isNaN())) ? "" : String.format("%.6f", v) }
csvFile.withWriter { w ->
    w.writeLine((["wsi_name", "cx_wsi", "cy_wsi"] + allFeatures.collect { shortName(it) }).join(","))
    detections.each { det ->
        def roi = det.getROI()
        def row = [name, fmt(roi.getCentroidX()), fmt(roi.getCentroidY())]
        allFeatures.each { m -> row << fmt(getMeasure(det, m)) }
        w.writeLine(row.join(","))
    }
}
print "Exported features -> " + csvFile.getPath()

// ---------------- 2. RUN PYTHON ----------------
def cmd = [PYTHON_EXE, PREDICT_PY,
           "--model", MODEL_PATH,
           "--csv", csvFile.getAbsolutePath(),
           "--out", jsonFile.getAbsolutePath(),
           "--min_confidence", MIN_CONFIDENCE.toString()]
print "Running: " + cmd.join(" ")

def pb = new ProcessBuilder(cmd)
pb.redirectErrorStream(true)
def proc = pb.start()
proc.getInputStream().eachLine { line -> print "  [python] " + line }
boolean finished = proc.waitFor(TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
if (!finished) {
    proc.destroyForcibly()
    print "ERROR: Python timed out after " + TIMEOUT_MINUTES + " minutes."
    return
}
if (proc.exitValue() != 0) {
    print "ERROR: Python exited with code " + proc.exitValue() + " (see [python] output above)."
    return
}
if (!jsonFile.exists()) { print "ERROR: no predictions file produced."; return }

// ---------------- 3. IMPORT PREDICTIONS ----------------
// groovy.json is a separate module and isn't available in every QuPath
// build, so the predictions file is parsed directly. This is safe only
// because predict_nuclei.py writes it - the format is known and fixed:
//   {"predictions": [{"cx": <num>, "cy": <num>, "cls": "Name"|null, "conf": <num>}, ...]}
def jsonText = jsonFile.getText("UTF-8")
def predsStart = jsonText.indexOf('"predictions"')
if (predsStart < 0) { print "ERROR: malformed predictions file (no 'predictions' key)."; return }
def arrStart = jsonText.indexOf('[', predsStart)
def arrEnd = jsonText.lastIndexOf(']')
if (arrStart < 0 || arrEnd <= arrStart) { print "ERROR: malformed predictions array."; return }
def body = jsonText.substring(arrStart + 1, arrEnd)

def objPattern = java.util.regex.Pattern.compile(/\{[^{}]*\}/)
def numPattern = { String obj, String key ->
    def m = java.util.regex.Pattern.compile('"' + key + '"\\s*:\\s*(-?[0-9.eE+]+)').matcher(obj)
    return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN
}
def strOrNull = { String obj, String key ->
    def m = java.util.regex.Pattern.compile('"' + key + '"\\s*:\\s*(null|"((?:[^"\\\\]|\\\\.)*)")').matcher(obj)
    if (!m.find()) return null
    if (m.group(1) == "null") return null
    return m.group(2).replace('\\"', '"').replace('\\\\', '\\')
}

def preds = []
def matcher = objPattern.matcher(body)
while (matcher.find()) {
    def obj = matcher.group()
    preds << [cx: numPattern(obj, "cx"), cy: numPattern(obj, "cy"),
              cls: strOrNull(obj, "cls"), conf: numPattern(obj, "conf")]
}
print "Predictions returned: " + preds.size()
if (preds.size() != detections.size()) {
    print "ERROR: prediction count (" + preds.size() + ") != detection count (" +
          detections.size() + "). Not applying."
    return
}

// CSV rows were written in detection order and Python preserves it, but the
// coordinates are checked anyway - a silent misalignment would mislabel
// every nucleus.
double maxDrift = 0.0
for (int i = 0; i < detections.size(); i++) {
    def roi = detections[i].getROI()
    double d = Math.max(Math.abs(roi.getCentroidX() - (preds[i].cx as double)),
                        Math.abs(roi.getCentroidY() - (preds[i].cy as double)))
    maxDrift = Math.max(maxDrift, d)
}
if (maxDrift > 1.0) {
    print "ERROR: predictions do not line up with detections (max drift " +
          String.format("%.2f", maxDrift) + " px). Not applying."
    return
}

if (BACKUP_EXISTING_CLASSES) {
    def seen = [:]
    int nextId = 1
    detections.each { det ->
        def nm = det.getPathClass()?.getName()
        if (nm != null) {
            if (!seen.containsKey(nm)) seen[nm] = nextId++
            det.getMeasurementList().put("orig_class_id", seen[nm] as double)
        }
    }
    if (!seen.isEmpty()) print "Backed up original classes as orig_class_id: " + seen
}

def counts = [:].withDefault { 0 }
for (int i = 0; i < detections.size(); i++) {
    def det = detections[i]
    def cls = preds[i].cls
    det.setPathClass(cls == null ? null : getPathClass(cls as String))
    det.getMeasurementList().put("pred_confidence", preds[i].conf as double)
    counts[cls ?: "(below threshold)"]++
}
fireHierarchyUpdate()

print "--- Applied ---"
counts.sort { -it.value }.each { k, v -> print "  " + k + ": " + v }

try {
    getProjectEntry()?.saveImageData(imageData)
    print "Saved."
} catch (Exception e) {
    print "Could not auto-save (" + e.getMessage() + ") - save manually."
}
print "Done. Sort detections by 'pred_confidence' to review uncertain calls."
