import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

def plane = ImagePlane.getDefaultPlane()

def existing = getAnnotationObjects()
if (!existing.isEmpty()) {
    removeObjects(existing, false)   // false = 자식 detection도 함께 삭제
    println "기존 ROI ${existing.size()}개(및 검출 결과)를 삭제했습니다."
}

// x, y = 시작 좌표(픽셀), width, height = 원하는 크기(픽셀)
def roi = ROIs.createEllipseROI(500, 500, 1440, 1440, plane)
def annotation = PathObjects.createAnnotationObject(roi)
addObject(annotation)