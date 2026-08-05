import qupath.lib.objects.PathObject
import qupath.lib.io.GsonTools
import com.google.gson.reflect.TypeToken
import java.util.zip.GZIPInputStream
import static groovy.io.FileType.FILES
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

def json_root_dir = '/mnt/NAS/PDL1-2026-Detections/TNBC-D/cellpose-dino'
def imageData = getCurrentImageData()
def server = imageData.getServer()
def name = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())

def json_dir_path = buildFilePath(json_root_dir, name)
def dir = new File(json_dir_path)
if (!dir.isDirectory()) {
    print "Directory not found: ${json_dir_path}"
    return
}

// ---- Build prepared annotation geometries (union) to test detections against ----
def annotations = getAnnotationObjects()
if (annotations.isEmpty()) {
    print "No annotations on ${name} — skipping import to avoid unfiltered detections."
    return
}
def annotationGeometries = annotations.collect { it.getROI().getGeometry() }
def unionGeometry = annotationGeometries.size() == 1 ? annotationGeometries[0] : annotationGeometries[1..-1].inject(annotationGeometries[0]) { acc, g -> acc.union(g) }
def preparedGeom = PreparedGeometryFactory.prepare(unionGeometry)

def gson = GsonTools.getInstance(true)
def type = new TypeToken<List<PathObject>>() {}.getType()
int total = 0
int skipped = 0

dir.eachFileRecurse(FILES) { file ->
    if (file.name.endsWith('.geojson.gz')) {
        print "\nImporting from ${file}"
        new GZIPInputStream(new FileInputStream(file)).withReader('UTF-8') { reader ->
            List<PathObject> objects = gson.fromJson(reader, type)
            def filtered = objects.findAll { obj ->
                def roi = obj.getROI()
                roi != null && preparedGeom.intersects(roi.getGeometry())
            }
            skipped += (objects.size() - filtered.size())
            addObjects(filtered)
            total += filtered.size()
            print "\tImported ${filtered.size()} / ${objects.size()} objects (skipped ${objects.size() - filtered.size()} outside annotations)"
        }
    }
}
resolveHierarchy()
print "\nDone. Imported ${total} objects total (${skipped} skipped as outside annotations)."