package com.hotupdater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result wrapper for download operations
 * 다운로드 작업의 결과를 나타내는 sealed 클래스
 * 성공(Success)과 실패(Error) 두 가지 상태를 표현함
 */
sealed class DownloadResult {
    /**
     * 다운로드 성공 시 반환되는 클래스
     * @property file 다운로드된 파일 객체
     */
    data class Success(
        val file: File,
    ) : DownloadResult()

    /**
     * 다운로드 실패 시 반환되는 클래스
     * @property exception 발생한 예외 객체
     */
    data class Error(
        val exception: Exception,
    ) : DownloadResult()
}

/**
 * Interface for download operations
 * 다운로드 작업을 위한 인터페이스
 * 다양한 다운로드 구현체를 위한 공통 계약 정의
 */
interface DownloadService {
    /**
     * Downloads a file from a URL
     * URL에서 파일을 다운로드하는 메서드
     * 
     * @param fileUrl 다운로드할 파일의 URL
     * @param destination 다운로드한 파일을 저장할 로컬 경로
     * @param progressCallback 다운로드 진행 상황을 보고받을 콜백 함수(0.0~1.0 사이의 진행률)
     * @return Result indicating success or failure (DownloadResult 객체로 성공 또는 실패 반환)
     */
    suspend fun downloadFile(
        fileUrl: URL,
        destination: File,
        progressCallback: (Double) -> Unit,
    ): DownloadResult
}

/**
 * Implementation of DownloadService using HttpURLConnection
 * HttpURLConnection을 사용한 DownloadService 구현체
 * 안드로이드에서 HTTP를 통해 파일을 다운로드하는 실제 로직 포함
 */
class HttpDownloadService : DownloadService {
    /**
     * HttpURLConnection을 사용하여 파일을 다운로드하는 메서드 구현
     * 
     * @param fileUrl 다운로드할 파일의 URL
     * @param destination 다운로드한 파일을 저장할 로컬 경로
     * @param progressCallback 다운로드 진행 상황을 보고받을 콜백 함수
     * @return 다운로드 결과 (성공 또는 실패)
     */
    override suspend fun downloadFile(
        fileUrl: URL,
        destination: File,
        progressCallback: (Double) -> Unit,
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            // HttpURLConnection 객체 생성 및 연결 시도
            val conn =
                try {
                    fileUrl.openConnection() as HttpURLConnection
                } catch (e: Exception) {
                    Log.d("DownloadService", "Failed to open connection: ${e.message}")
                    return@withContext DownloadResult.Error(e)
                }

            try {
                conn.connect()
                // 파일 크기 확인
                val totalSize = conn.contentLength
                if (totalSize <= 0) {
                    Log.d("DownloadService", "Invalid content length: $totalSize")
                    return@withContext DownloadResult.Error(Exception("Invalid content length: $totalSize"))
                }

                // 대상 파일의 부모 디렉토리가 없으면 생성
                destination.parentFile?.mkdirs()

                // 입력 스트림과 출력 스트림을 사용하여 파일 다운로드
                conn.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        // 8KB 버퍼 사용하여 효율적인 다운로드
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressTime = System.currentTimeMillis()

                        // 데이터를 읽고 파일에 쓰는 반복 작업
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            // 100ms마다 진행률 업데이트 (UI 스레드 과부하 방지)
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressTime >= 100) {
                                val progress = totalRead.toDouble() / totalSize
                                progressCallback.invoke(progress)
                                lastProgressTime = currentTime
                            }
                        }
                        // 다운로드 완료 시 100% 진행률 전달
                        progressCallback.invoke(1.0)
                    }
                }
                // 다운로드 성공 결과 반환
                DownloadResult.Success(destination)
            } catch (e: Exception) {
                // 다운로드 중 발생한 예외 로깅 및 에러 결과 반환
                Log.d("DownloadService", "Failed to download data from URL: $fileUrl, Error: ${e.message}")
                DownloadResult.Error(e)
            } finally {
                // 연결 리소스 해제
                conn.disconnect()
            }
        }
}
