package com.hotupdater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * 번들 저장소 운영을 위한 인터페이스
 * React Native 앱의 JS 번들 파일을 관리하기 위한 기본 계약을 정의
 */
interface BundleStorageService {
    /**
     * 현재 번들 URL을 설정
     * @param localPath 번들 파일의 경로 (또는 리셋하려면 null)
     * @return 작업이 성공했으면 true
     */
    fun setBundleURL(localPath: String?): Boolean

    /**
     * 캐시된 번들 파일의 URL을 가져옴
     * @return 캐시된 번들의 경로 또는 찾을 수 없으면 null
     */
    fun getCachedBundleURL(): String?

    /**
     * 앱에 포함된 폴백 번들의 URL을 가져옴
     * @return 폴백 번들 경로
     */
    fun getFallbackBundleURL(): String

    /**
     * 번들 파일의 URL을 가져옴 (캐시된 것 또는 폴백)
     * @return 번들 파일의 경로
     */
    fun getBundleURL(): String

    /**
     * 지정된 URL에서 번들을 업데이트
     * @param bundleId 업데이트할 번들의 ID
     * @param fileUrl 다운로드할 번들 파일의 URL (또는 리셋하려면 null)
     * @param progressCallback 다운로드 진행률 업데이트를 위한 콜백
     * @return 업데이트가 성공했으면 true
     */
    suspend fun updateBundle(
        bundleId: String,
        fileUrl: String?,
        progressCallback: (Double) -> Unit,
    ): Boolean
}

/**
 * BundleStorageService의 구현체
 * Hot Update 기능을 위한 번들 파일 저장 및 관리를 담당
 * 
 * @param fileSystem 파일 시스템 작업을 위한 서비스
 * @param downloadService 파일 다운로드를 위한 서비스
 * @param unzipService ZIP 파일 압축 해제를 위한 서비스
 * @param preferences 설정 저장을 위한 서비스
 */
class BundleFileStorageService(
    private val fileSystem: FileSystemService,
    private val downloadService: DownloadService,
    private val unzipService: UnzipService,
    private val preferences: PreferencesService,
) : BundleStorageService {
    
    /**
     * 번들 URL을 SharedPreferences에 저장
     * Hot Update된 번들의 로컬 경로를 기록하여 앱 재시작 시에도 유지
     */
    override fun setBundleURL(localPath: String?): Boolean {
        preferences.setItem("HotUpdaterBundleURL", localPath)
        return true
    }

    /**
     * 캐시된 번들 URL을 SharedPreferences에서 가져옴
     * 파일이 실제로 존재하는지 확인하고, 없으면 캐시를 정리
     */
    override fun getCachedBundleURL(): String? {
        val urlString = preferences.getItem("HotUpdaterBundleURL")
        if (urlString.isNullOrEmpty()) {
            return null
        }

        // 파일이 실제로 존재하는지 확인
        val file = File(urlString)
        if (!file.exists()) {
            // 파일이 없으면 캐시된 URL을 제거
            preferences.setItem("HotUpdaterBundleURL", null)
            return null
        }
        return urlString
    }

    /**
     * 앱에 번들된 기본 JS 번들 파일의 경로 반환
     * Hot Update가 실패하거나 없을 때 사용되는 안전망
     */
    override fun getFallbackBundleURL(): String = "assets://index.android.bundle"

    /**
     * 현재 사용할 번들 URL 반환
     * 우선순위: 캐시된 번들 > 폴백 번들
     */
    override fun getBundleURL(): String = getCachedBundleURL() ?: getFallbackBundleURL()

    /**
     * 새로운 번들을 다운로드하고 설치하는 메인 메서드
     * 전체 Hot Update 프로세스를 관리
     */
    override suspend fun updateBundle(
        bundleId: String,
        fileUrl: String?,
        progressCallback: (Double) -> Unit,
    ): Boolean {
        Log.d("BundleStorage", "updateBundle bundleId $bundleId fileUrl $fileUrl")

        // fileUrl이 null이면 번들을 리셋 (폴백 번들 사용)
        if (fileUrl.isNullOrEmpty()) {
            setBundleURL(null)
            return true
        }

        // 번들 저장을 위한 디렉토리 구조 설정
        val baseDir = fileSystem.getExternalFilesDir()
        val bundleStoreDir = File(baseDir, "bundle-store")
        if (!bundleStoreDir.exists()) {
            bundleStoreDir.mkdirs()
        }

        // 각 번들은 bundleId로 구분된 별도 폴더에 저장
        val finalBundleDir = File(bundleStoreDir, bundleId)
        
        // 이미 해당 bundleId의 번들이 있는지 확인
        if (finalBundleDir.exists()) {
            Log.d("BundleStorage", "Bundle for bundleId $bundleId already exists. Using cached bundle.")
            val existingIndexFile = finalBundleDir.walk().find { it.name == "index.android.bundle" }
            if (existingIndexFile != null) {
                // 기존 번들이 유효하면 최근 사용 시간을 업데이트하고 사용
                finalBundleDir.setLastModified(System.currentTimeMillis())
                setBundleURL(existingIndexFile.absolutePath)
                cleanupOldBundles(bundleStoreDir) // 오래된 번들 정리
                return true
            } else {
                // 번들 파일이 손상되었으면 디렉토리 삭제
                finalBundleDir.deleteRecursively()
            }
        }

        // 임시 다운로드 디렉토리 설정
        val tempDir = File(baseDir, "bundle-temp")
        if (tempDir.exists()) {
            tempDir.deleteRecursively() // 이전 임시 파일 정리
        }
        tempDir.mkdirs()

        val tempZipFile = File(tempDir, "bundle.zip")
        val extractedDir = File(tempDir, "extracted")
        extractedDir.mkdirs()

        // 백그라운드 스레드에서 다운로드 및 압축 해제 수행
        return withContext(Dispatchers.IO) {
            val downloadUrl = URL(fileUrl)

            // 1단계: ZIP 파일 다운로드
            val downloadResult =
                downloadService.downloadFile(
                    downloadUrl,
                    tempZipFile,
                    progressCallback,
                )

            when (downloadResult) {
                is DownloadResult.Error -> {
                    Log.d("BundleStorage", "Download failed: ${downloadResult.exception.message}")
                    tempDir.deleteRecursively()
                    return@withContext false
                }
                is DownloadResult.Success -> {
                    // 2단계: ZIP 파일 압축 해제
                    if (!unzipService.extractZipFile(tempZipFile.absolutePath, extractedDir.absolutePath)) {
                        Log.d("BundleStorage", "Failed to extract zip file.")
                        tempDir.deleteRecursively()
                        return@withContext false
                    }

                    // 3단계: 번들 파일 검증
                    val indexFileExtracted = extractedDir.walk().find { it.name == "index.android.bundle" }
                    if (indexFileExtracted == null) {
                        Log.d("BundleStorage", "index.android.bundle not found in extracted files.")
                        tempDir.deleteRecursively()
                        return@withContext false
                    }

                    // 4단계: 최종 위치로 파일 이동
                    if (finalBundleDir.exists()) {
                        finalBundleDir.deleteRecursively()
                    }

                    // 파일 이동 시도, 실패하면 복사 후 원본 삭제
                    if (!fileSystem.moveItem(extractedDir.absolutePath, finalBundleDir.absolutePath)) {
                        fileSystem.copyItem(extractedDir.absolutePath, finalBundleDir.absolutePath)
                        extractedDir.deleteRecursively()
                    }

                    // 5단계: 최종 번들 파일 검증
                    val finalIndexFile = finalBundleDir.walk().find { it.name == "index.android.bundle" }
                    if (finalIndexFile == null) {
                        Log.d("BundleStorage", "index.android.bundle not found in final directory.")
                        tempDir.deleteRecursively()
                        return@withContext false
                    }

                    // 6단계: 번들 URL 업데이트 및 정리
                    finalBundleDir.setLastModified(System.currentTimeMillis())
                    val bundlePath = finalIndexFile.absolutePath
                    Log.d("BundleStorage", "Setting bundle URL: $bundlePath")
                    setBundleURL(bundlePath) // 새 번들을 활성화
                    cleanupOldBundles(bundleStoreDir) // 오래된 번들 정리
                    tempDir.deleteRecursively() // 임시 파일 정리

                    Log.d("BundleStorage", "Downloaded and extracted file successfully.")
                    return@withContext true
                }
            }
        }
    }

    /**
     * 오래된 번들들을 정리하여 저장 공간 절약
     * 가장 최근에 사용된 번들 1개만 보관하고 나머지는 삭제
     */
    private fun cleanupOldBundles(bundleStoreDir: File) {
        val bundles = bundleStoreDir.listFiles { file -> file.isDirectory }?.toList() ?: return
        val sortedBundles = bundles.sortedByDescending { it.lastModified() } // 최근 사용 순으로 정렬
        
        if (sortedBundles.size > 1) {
            // 가장 최근 번들을 제외한 나머지 삭제
            sortedBundles.drop(1).forEach { oldBundle ->
                Log.d("BundleStorage", "Removing old bundle: ${oldBundle.name}")
                oldBundle.deleteRecursively()
            }
        }
    }
}
