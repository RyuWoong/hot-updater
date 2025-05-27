package com.hotupdater

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * HotUpdater 기능의 핵심 구현 클래스
 * React Native 애플리케이션의 동적 업데이트(Hot Update) 기능을 담당합니다.
 * 
 * @param context 애플리케이션 컨텍스트
 * @param bundleStorage 번들 저장 서비스 - JS 번들 파일을 관리
 * @param preferences 환경설정 서비스 - 사용자 설정을 저장/조회
 */
class HotUpdaterImpl(
    private val context: Context,
    private val bundleStorage: BundleStorageService,
    private val preferences: PreferencesService,
) {
    /**
     * 현재 설치된 앱의 버전을 가져옵니다
     * Android의 PackageManager를 사용하여 앱의 versionName을 조회합니다
     * 
     * @return 앱 버전명 (예: "1.0.0") 또는 조회 실패 시 null
     */
    fun getAppVersion(): String? =
        try {
            // Android 13 (API 33) 이상에서는 새로운 API 사용
            val packageInfo =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags
                            .of(0),
                    )
                } else {
                    // 이전 버전에서는 deprecated된 메서드 사용
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            packageInfo.versionName
        } catch (e: Exception) {
            // 패키지 정보 조회 실패 시 null 반환
            null
        }

    /**
     * 정적 메서드로 앱 버전을 가져옵니다
     * 인스턴스 생성 없이도 앱 버전을 조회할 수 있도록 companion object에 정의
     * 
     * @param context 애플리케이션 컨텍스트
     * @return 앱 버전명 또는 조회 실패 시 null
     */
    companion object {
        fun getAppVersion(context: Context): String? =
            try {
                val packageInfo =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(
                            context.packageName,
                            android.content.pm.PackageManager.PackageInfoFlags
                                .of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                packageInfo.versionName
            } catch (e: Exception) {
                null
            }
    }

    /**
     * 빌드 타임스탬프를 기반으로 최소 번들 ID를 생성합니다
     * UUID 형태의 고유한 식별자를 생성하여 번들 버전을 구분합니다
     * 
     * 생성 과정:
     * 1. BuildConfig.BUILD_TIMESTAMP에서 빌드 시간을 가져옴
     * 2. 타임스탬프를 16바이트 배열로 변환 (상위 6바이트는 타임스탬프, 나머지는 고정값)
     * 3. UUID 형태의 문자열로 포맷팅
     * 
     * @return UUID 형태의 번들 ID 문자열 (예: "12345678-9abc-7000-8000-000000000000")
     */
    fun getMinBundleId(): String =
        try {
            val buildTimestampMs = BuildConfig.BUILD_TIMESTAMP
            // 16바이트 배열 생성 및 타임스탬프 데이터 설정
            val bytes =
                ByteArray(16).apply {
                    // 타임스탬프를 6바이트로 분할하여 저장 (상위 비트부터)
                    this[0] = ((buildTimestampMs shr 40) and 0xFF).toByte()
                    this[1] = ((buildTimestampMs shr 32) and 0xFF).toByte()
                    this[2] = ((buildTimestampMs shr 24) and 0xFF).toByte()
                    this[3] = ((buildTimestampMs shr 16) and 0xFF).toByte()
                    this[4] = ((buildTimestampMs shr 8) and 0xFF).toByte()
                    this[5] = (buildTimestampMs and 0xFF).toByte()
                    // UUID 버전 7 표시 (시간 기반)
                    this[6] = 0x70.toByte()
                    this[7] = 0x00.toByte()
                    // UUID 변형 비트 설정
                    this[8] = 0x80.toByte()
                    // 나머지 바이트는 0으로 초기화
                    this[9] = 0x00.toByte()
                    this[10] = 0x00.toByte()
                    this[11] = 0x00.toByte()
                    this[12] = 0x00.toByte()
                    this[13] = 0x00.toByte()
                    this[14] = 0x00.toByte()
                    this[15] = 0x00.toByte()
                }
            // UUID 형태로 포맷팅 (8-4-4-4-12 형태)
            String.format(
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                bytes[0].toInt() and 0xFF,
                bytes[1].toInt() and 0xFF,
                bytes[2].toInt() and 0xFF,
                bytes[3].toInt() and 0xFF,
                bytes[4].toInt() and 0xFF,
                bytes[5].toInt() and 0xFF,
                bytes[6].toInt() and 0xFF,
                bytes[7].toInt() and 0xFF,
                bytes[8].toInt() and 0xFF,
                bytes[9].toInt() and 0xFF,
                bytes[10].toInt() and 0xFF,
                bytes[11].toInt() and 0xFF,
                bytes[12].toInt() and 0xFF,
                bytes[13].toInt() and 0xFF,
                bytes[14].toInt() and 0xFF,
                bytes[15].toInt() and 0xFF,
            )
        } catch (e: Exception) {
            // 오류 발생 시 기본 UUID 반환
            "00000000-0000-0000-0000-000000000000"
        }

    /**
     * 업데이트 채널을 설정합니다
     * 다양한 배포 환경(dev, staging, production 등)을 구분하기 위해 사용
     * 
     * @param channel 설정할 채널명 (예: "production", "staging", "development")
     */
    fun setChannel(channel: String) {
        preferences.setItem("HotUpdaterChannel", channel)
    }

    /**
     * 현재 설정된 업데이트 채널을 조회합니다
     * 
     * @return 현재 채널명 또는 설정되지 않은 경우 null
     */
    fun getChannel(): String? = preferences.getItem("HotUpdaterChannel")

    /**
     * JavaScript 번들 파일의 경로를 가져옵니다
     * React Native 앱이 로드할 JS 번들의 위치를 반환
     * 
     * @return 번들 파일의 전체 경로
     */
    fun getJSBundleFile(): String = bundleStorage.getBundleURL()

    /**
     * 지정된 URL에서 번들을 다운로드하여 업데이트합니다
     * 비동기 작업으로 처리되며, 진행 상황을 콜백으로 알려줍니다
     * 
     * @param bundleId 업데이트할 번들의 고유 ID
     * @param fileUrl 다운로드할 번들 파일의 URL (null인 경우 초기화)
     * @param progressCallback 다운로드 진행률을 받는 콜백 함수 (0.0 ~ 1.0)
     * @return 업데이트 성공 여부
     */
    suspend fun updateBundle(
        bundleId: String,
        fileUrl: String?,
        progressCallback: (Double) -> Unit,
    ): Boolean = bundleStorage.updateBundle(bundleId, fileUrl, progressCallback)

    /**
     * React Native 애플리케이션을 다시 로드합니다
     * 새로운 번들이 적용되도록 앱을 재시작합니다
     * 
     * 처리 과정:
     * 1. ReactIntegrationManager를 통해 React Native 앱 인스턴스 조회
     * 2. 새로운 번들 URL 설정
     * 3. 메인 스레드에서 앱 리로드 실행
     * 
     * @param activity 현재 액티비티 (선택사항, null일 수 있음)
     */
    fun reload(activity: Activity? = null) {
        val reactIntegrationManager = ReactIntegrationManager(context)
        val application = activity?.application ?: return

        try {
            // React Native 애플리케이션 인스턴스 가져오기
            val reactApplication = reactIntegrationManager.getReactApplication(application)
            val bundleURL = getJSBundleFile()

            // 새로운 번들 URL 설정
            reactIntegrationManager.setJSBundle(reactApplication, bundleURL)

            // 메인 스레드에서 리로드 실행
            Handler(Looper.getMainLooper()).post {
                reactIntegrationManager.reload(reactApplication)
            }
        } catch (e: Exception) {
            Log.e("HotUpdaterImpl", "애플리케이션 리로드 실패", e)
        }
    }

    /**
     * ReactApplicationContext에서 현재 액티비티를 가져옵니다
     * 
     * 참고: 현재는 구현되지 않음 (순환 종속성 문제로 인해)
     * ReactApplicationContext가 필요하지만 이는 순환 종속성을 야기할 수 있어
     * 다른 방식으로 구현하거나 이동해야 함
     * 
     * @param context ReactApplicationContext일 수 있는 컨텍스트
     * @return 현재 액티비티 또는 null
     */
    @Suppress("UNUSED_PARAMETER")
    fun getCurrentActivity(context: Context): Activity? {
        // ReactApplicationContext가 필요하지만 순환 종속성 문제로 인해
        // 다르게 구현되거나 이동되어야 함
        return null
    }
}
