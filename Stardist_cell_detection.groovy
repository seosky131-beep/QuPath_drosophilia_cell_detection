/**
 * StarDist 기반 Repo+ 세포(핵) 검출(빨강) + 큰 객체 판정(다시 포함한 거는: 초록색 아예 지워진거는 보라색)
 * - Anti-Repo (488nm) 단일 채널, Drosophila mushroom body ROI
 * 
 * 인용: StarDist(https://doi.org/10.48550/arXiv.1806.03535),
 *       QuPath(https://doi.org/10.1038/s41598-017-17204-5)
 */
import qupath.ext.stardist.StarDist2D
import qupath.lib.scripting.QP
import qupath.lib.common.GeneralTools

setPixelSizeMicrons(0.1424, 0.1424)
// 픽셀 크기 강제 지정 (µm/px). 20x FOV 437.5 µm ÷ zoom 1.5 ÷ 2048 px
// → 이미지 metadata가 깨져도 area 파라미터가 정상 작동하게 하는 보험

// ---------- 1. 모델 파일 확인 ----------
def modelPath = "C:/Users/seosk/Downloads/dsb2018_heavy_augment.pb"
def modelFile = new File(modelPath)
if (!modelFile.exists()) {
    println "Error: 모델 파일을 찾을 수 없습니다: " + modelPath
    return
}

// ---------- 2. 이미지 확인 ----------
def imageData = QP.getCurrentImageData()
if (imageData == null) {
    println "Error: 열려 있는 이미지가 없습니다!"
    return
}
// 이미지 이름은 CSV 저장(8)과 최종 요약 출력(10)에서 모두 필요해서 여기서 미리 확보
def imageName = GeneralTools.getNameWithoutExtension(imageData.getServer().getMetadata().getName())

// ---------- 3. ROI 확인 ----------
QP.selectAnnotations()
def pathObjects = QP.getSelectedObjects()
if (pathObjects.isEmpty()) {
    println "Error: 선택된 ROI가 없습니다! 먼저 mushroom body를 그려주세요."
    return
}

// ---------- 4. 기존 검출 결과 제거 (중복 방지) ----------
def existing = QP.getDetectionObjects()
if (!existing.isEmpty()) {
    println "기존 검출 ${existing.size()}개를 삭제하고 다시 검출합니다."
    QP.removeObjects(existing, true)
}

// ---------- 5. StarDist 검출 ----------
def stardist = StarDist2D
    .builder(modelPath)
    .channels('Green')
    .normalizePercentiles(1, 99)
    .threshold(0.5)
    .pixelSize(0.5)
    .cellExpansion(0)
    .measureShape()      // Area, Circularity, Solidity, Max/Min diameter 등을 계산 (6단계에서 사용)
    .measureIntensity()
    .build()

stardist.detectObjects(imageData, pathObjects)
stardist.close()

def detections = QP.getDetectionObjects()
println "검출된 전체 객체 수: ${detections.size()}"

// 배치("Run for project") 도중 한 이미지에서 검출이 0개면 이후 detections[0] 접근에서
// 스크립트 전체가 죽어 배치가 중단될 수 있음 → 여기서 안전하게 건너뜀
if (detections.isEmpty()) {
    println "검출된 객체가 없습니다. 이 이미지는 건너뜁니다."
    return
}

// ============================================================
// 6. 큰 객체 판정 — 면적(area) + 밝기(intensity) + 모양(shape) 3차원 기준
// ============================================================

// ---------- 6-1. 밝기(intensity) 측정값 키 자동 탐색 ----------
def sampleKeys = detections[0].getMeasurementList().asMap().keySet()
def meanKeyCandidates = sampleKeys.findAll { it.toLowerCase().contains('mean') }
if (meanKeyCandidates.isEmpty()) {
    println "Error: 밝기(mean intensity) 측정값을 찾지 못했습니다. 사용 가능한 키: ${sampleKeys}"
    return
}
def meanKey = meanKeyCandidates.find { it.toLowerCase().contains('green') } ?: meanKeyCandidates[0]
println "사용할 밝기 측정값: '${meanKey}'"

// ---------- 6-2. 모양(shape) 측정값 키 자동 탐색 ----------
// solidity 우선 사용 (픽셀 경계의 들쭉날쭉함에 덜 민감). circularity는 둘레(perimeter)를
// 쓰므로 경계 노이즈에 더 민감해서 solidity가 없을 때만 차선책으로 사용.
def solidityCandidates    = sampleKeys.findAll { it.toLowerCase().contains('solidity') }
def circularityCandidates = sampleKeys.findAll { it.toLowerCase().contains('circular') }
def shapeKey = solidityCandidates ? solidityCandidates[0] : (circularityCandidates ? circularityCandidates[0] : null)
boolean useShape = (shapeKey != null)
if (useShape) {
    println "사용할 모양 측정값: '${shapeKey}'"
} else {
    println "Error: Solidity/Circularity 측정값을 찾지 못했습니다 (.measureShape() 확인 필요). 모양 기준 없이 진행합니다."
}

// ---------- 6-3. 면적 IQR 상한선 계산 ----------
def areas = detections.collect { it.getMeasurementList().get('Area µm^2') }
                       .findAll { it != null }
areas.sort()

if (areas.isEmpty()) {
    println "Error: 'Area µm^2' 측정값을 찾지 못했습니다. 사용 가능한 이름: ${sampleKeys}"
    return
}

int n = areas.size()
double q1Area = areas[(int)(n * 0.25)]
double q3Area = areas[(int)(n * 0.75)]
double iqrArea = q3Area - q1Area
double upperFence = q3Area + 1.5 * iqrArea
println "Area Q1=${q1Area}, Q3=${q3Area}, IQR=${iqrArea}, 상한선=${upperFence} µm²"

// ---------- 6-4. "정상 크기" 세포군의 밝기 · 모양 분포(baseline) 계산 ----------
def normalSized = detections.findAll {
    def a = it.getMeasurementList().get('Area µm^2')
    a != null && a <= upperFence
}

def normalIntensities = normalSized.collect { it.getMeasurementList().get(meanKey) }
                                    .findAll { it != null }
normalIntensities.sort()
if (normalIntensities.isEmpty()) {
    println "Error: 정상 크기 세포군에서 밝기 값을 얻지 못했습니다."
    return
}
int nn = normalIntensities.size()
double intensityFloor = normalIntensities[(int)(nn * 0.25)]
println "정상 세포군 밝기 Q1(=intensityFloor) = ${intensityFloor}"

double shapeFloor = 0.0
if (useShape) {
    def normalShapes = normalSized.collect { it.getMeasurementList().get(shapeKey) }
                                   .findAll { it != null }
    normalShapes.sort()
    if (!normalShapes.isEmpty()) {
        int ns = normalShapes.size()
        shapeFloor = normalShapes[(int)(ns * 0.25)]
        println "정상 세포군 모양(${shapeKey}) Q1(=shapeFloor) = ${shapeFloor}"
    } else {
        useShape = false
        println "Error: 정상 세포군에서 모양 값을 얻지 못해 모양 기준을 건너뜁니다."
    }
}

// ---------- 6-5. 면적 이상치를 밝기 + 모양 기준으로 재분류 ----------
def areaOutliers = detections.findAll {
    def a = it.getMeasurementList().get('Area µm^2')
    a != null && a > upperFence
}

def noiseClass       = QP.getPathClass('ReviewLarge_Noise')       // 배경/노이즈 추정 → 카운트에서 제외
def mergedClass       = QP.getPathClass('ReviewLarge_Merged')      // 뭉친 진짜세포 추정 → 유지, 수동검토 대상
def largeNormalClass  = QP.getPathClass('LargeNormal_NoReview')    // 크지만 둥글고 밝음 → 유지, 검토 불필요 (정보용)

int noiseCount = 0
int mergedCount = 0
int reclassifiedNormalCount = 0

areaOutliers.each { obj ->
    def intensity = obj.getMeasurementList().get(meanKey)
    def shapeVal  = useShape ? obj.getMeasurementList().get(shapeKey) : null

    if (intensity != null && intensity < intensityFloor) {
        // 어둡다 → 배경/노이즈 추정
        obj.setPathClass(noiseClass)
        noiseCount++
    } else if (useShape && shapeVal != null && shapeVal >= shapeFloor) {
        // 밝다 + 정상 세포군만큼 둥글다(solidity/circularity 정상 범위)
        // → 진짜 크고 둥근 단일 핵으로 판단, 플래그 없이 정상 취급
        obj.setPathClass(largeNormalClass)
        reclassifiedNormalCount++
    } else {
        // 밝지만 모양이 비정상적(찌그러짐/오목함) 또는 모양 정보 없음
        // → 뭉친 진짜세포로 추정, 수동검토 대상으로 유지
        obj.setPathClass(mergedClass)
        mergedCount++
    }
}
QP.fireHierarchyUpdate()

println "면적 이상치 총 ${areaOutliers.size()}개 중:"
println "  - 노이즈로 판정(제외): ${noiseCount}개"
println "  - 뭉친 진짜세포로 판정(유지, 수동검토 권장): ${mergedCount}개"
println "  - 크지만 둥글고 밝아 '정상'으로 재분류(플래그 없음): ${reclassifiedNormalCount}개"
println "정제된 카운트(= 전체 − 노이즈만) = ${detections.size() - noiseCount}"
if (mergedCount > 0) {
    println "⚠️ '뭉친 진짜세포(${mergedCount}개)'는 실제로는 세포 1개가 아니라 2개 이상일 수 있습니다."
    println "   가능하면 QuPath에서 해당 객체들을 직접 열어 육안으로 확인하세요."
}

// ============================================================
// 7. ROI별 통계 계산 — 한 번만 계산해서 CSV(8) · measurement(9) · 최종요약(10)에서 재사용
// ============================================================
def roiStats = []
pathObjects.eachWithIndex { roi, i ->
    def children = roi.getChildObjects()
    def total = children.size()
    def excludedNoise = children.findAll { it.getPathClass() == noiseClass }.size()
    def flaggedMerged = children.findAll { it.getPathClass() == mergedClass }.size()
    def largeNormal   = children.findAll { it.getPathClass() == largeNormalClass }.size()
    def cleaned = total - excludedNoise
    roiStats << [roi: roi, index: i, total: total, excludedNoise: excludedNoise,
                 flaggedMerged: flaggedMerged, largeNormal: largeNormal, cleaned: cleaned]
}

// ============================================================
// 8. CSV 저장 — Cleaned_count(= 실제 사용할 숫자)는 각 줄의 마지막 열
// ============================================================
if (QP.PROJECT_BASE_DIR == null) {
    println "Error: 프로젝트가 열려있지 않아 CSV 저장을 건너뜁니다."
} else {
    def outPath = QP.buildFilePath(QP.PROJECT_BASE_DIR, 'all_cellcounts.csv')
    def outFile = new File(outPath)
    synchronized (QP.class) {
        boolean writeHeader = !outFile.exists() || outFile.length() == 0
        outFile.withWriterAppend('UTF-8') { writer ->
            if (writeHeader) {
                writer.writeLine("Image,ROI_index,Total_count,Excluded_noise,Flagged_merged,Reclassified_large_normal,Cleaned_count")
            }
            roiStats.each { s ->
                writer.writeLine("${imageName},${s.index},${s.total},${s.excludedNoise},${s.flaggedMerged},${s.largeNormal},${s.cleaned}")
            }
        }
    }
    println "CSV에 결과 추가 완료: ${outPath}"
}

// ============================================================
// 9. annotation(ROI)에 측정값 기록
//    - Total / Excluded(noise) / Flagged(merged) / Reclassified 값은
//      QuPath가 자동으로 만들어주는 'Num Detections', 'Num ReviewLarge_Noise',
//      'Num LargeNormal_NoReview' 컬럼과 값이 완전히 동일한 중복이라 제거함
//    - 커스텀으로 남기는 값은 'Cleaned cell count'(실제 통계에 쓸 숫자) 하나뿐
//      → 유일한 커스텀 컬럼이므로 Export 결과에서 항상 맨 마지막에 위치함
//    - 예전 Watershed 스크립트에서 남아있던 컬럼들도 함께 정리
// ============================================================
def obsoleteKeys = [
    'Excluded (large) count',
    'Excluded (no intensity peak) count',
    'Split-resolved count',
    'Watershed threshold used',
    'Total cell count',
    'Excluded (noise) count',
    'Flagged (merged) count',
    'Reclassified large-normal count',
    // 순서를 항상 마지막으로 고정하기 위해 기존 값도 지우고 다시 씀
    'Cleaned cell count',
]

roiStats.each { s ->
    def ml = s.roi.getMeasurementList()
    try {
        ml.removeMeasurements(*obsoleteKeys)
    } catch (Exception e) {
        println "참고: 오래된 측정값 제거 중 경고(무시 가능): ${e.message}"
    }
    ml.put('Cleaned cell count', s.cleaned as double)   // ⭐ 실제 통계에 쓸 숫자 — 항상 맨 마지막 컬럼
    ml.close()
}
QP.fireHierarchyUpdate()
println "ROI별 Cleaned cell count를 measurement로 기록했습니다 (중복/구버전 컬럼 정리 완료)."

println('완료!')