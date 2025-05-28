package com.hotupdater

import android.content.Context

/**
 * HotUpdaterImpl 인스턴스를 생성하기 위한 팩토리 클래스
 * 의존성 주입을 통해 적절한 구현체를 제공합니다.
 */
object HotUpdaterFactory {
    /**
     * HotUpdaterImpl의 싱글톤 인스턴스를 저장하는 변수
     * @Volatile 어노테이션은 멀티스레드 환경에서 변수의 가시성을 보장합니다.
     */
    @Volatile
    private var instance: HotUpdaterImpl? = null

    /**
     * HotUpdaterImpl의 싱글톤 인스턴스를 가져옵니다.
     * 이중 확인 잠금(Double-checked locking) 패턴을 사용하여 스레드 안전성을 보장합니다.
     * 
     * @param context 애플리케이션 컨텍스트
     * @return HotUpdaterImpl 싱글톤 인스턴스
     */
    fun getInstance(context: Context): HotUpdaterImpl =
        instance ?: synchronized(this) {
            instance ?: createHotUpdaterImpl(context).also { instance = it }
        }

    /**
     * 모든 필요한 의존성을 주입하여 HotUpdaterImpl 인스턴스를 생성합니다.
     * 
     * @param context 애플리케이션 컨텍스트
     * @return 새로운 HotUpdaterImpl 인스턴스
     */
    private fun createHotUpdaterImpl(context: Context): HotUpdaterImpl {
        // 애플리케이션 컨텍스트와 앱 버전 정보를 가져옵니다.
        val appContext = context.applicationContext
        val appVersion = HotUpdaterImpl.getAppVersion(appContext) ?: "unknown"

        // 필요한 서비스 인스턴스들을 생성합니다.
        val fileSystem = FileManagerService(appContext)  // 파일 시스템 관리 서비스
        val preferences = VersionedPreferencesService(appContext, appVersion)  // 버전별 설정 관리 서비스
        val downloadService = HttpDownloadService()  // HTTP 다운로드 서비스
        val unzipService = ZipFileUnzipService()  // ZIP 파일 압축 해제 서비스

        // 번들 저장소 서비스를 생성하고 필요한 의존성을 주입합니다.
        val bundleStorage =
            BundleFileStorageService(
                fileSystem,  // 파일 관리를 위한 서비스
                downloadService,  // 번들 다운로드를 위한 서비스
                unzipService,  // 다운로드된 ZIP 번들 압축 해제를 위한 서비스
                preferences,  // 설정 저장을 위한 서비스
            )

        // 최종적으로 HotUpdaterImpl 인스턴스를 생성하여 반환합니다.
        return HotUpdaterImpl(appContext, bundleStorage, preferences)
    }
}
