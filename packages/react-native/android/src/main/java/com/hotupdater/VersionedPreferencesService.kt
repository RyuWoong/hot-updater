package com.hotupdater

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 환경설정 저장소 작업을 위한 인터페이스
 * Interface for preference storage operations
 */
interface PreferencesService {
    /**
     * 저장된 환경설정 값을 가져옵니다
     * Gets a stored preference value
     * @param key 조회할 키
     * @return 저장된 값 또는 없으면 null
     */
    fun getItem(key: String): String?

    /**
     * 환경설정 값을 저장합니다
     * Sets a preference value
     * @param key 저장할 키
     * @param value 저장할 값 (null이면 삭제)
     */
    fun setItem(
        key: String,
        value: String?,
    )
}

/**
 * SharedPreferences를 사용한 PreferencesService 구현체
 * 앱 버전별로 독립적인 설정 저장공간을 제공하고, 이전 버전의 설정 파일들을 자동으로 정리합니다
 * Implementation of PreferencesService using SharedPreferences
 * Modified from original HotUpdaterPrefs to follow the service pattern
 */
class VersionedPreferencesService(
    private val context: Context,      // 안드로이드 앱 컨텍스트
    private val appVersion: String,    // 현재 앱 버전 (예: "1.0.0")
) : PreferencesService {
    private val prefs: SharedPreferences  // 실제 데이터를 저장할 SharedPreferences

    init {
        // 앱 버전별로 고유한 환경설정 파일명 생성 (예: "HotUpdaterPrefs_1.0.0")
        val prefsName = "HotUpdaterPrefs_$appVersion"

        // shared_prefs 디렉터리 경로 가져오기
        // 안드로이드에서 SharedPreferences 파일들이 저장되는 위치
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        
        // shared_prefs 디렉터리가 존재하고 디렉터리인지 확인
        if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
            // 디렉터리 내의 모든 파일들을 순회
            sharedPrefsDir.listFiles()?.forEach { file ->
                // "HotUpdaterPrefs_"로 시작하지만 현재 버전 파일이 아닌 경우
                // 즉, 이전 버전들의 설정 파일들을 찾아서 삭제
                if (file.name.startsWith("HotUpdaterPrefs_") && file.name != "$prefsName.xml") {
                    file.delete() // 이전 버전의 설정 파일 삭제 (디스크 공간 절약)
                }
            }
        }

        // 현재 앱 버전용 SharedPreferences 인스턴스 생성
        // MODE_PRIVATE: 이 앱에서만 접근 가능한 전용 모드
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    /**
     * 키에 해당하는 저장된 값을 조회합니다
     * @param key 조회할 키
     * @return 저장된 문자열 값 또는 null (없는 경우)
     */
    override fun getItem(key: String): String? = prefs.getString(key, null)

    /**
     * 키-값 쌍을 저장하거나 삭제합니다
     * @param key 저장할 키
     * @param value 저장할 값 (null이면 해당 키를 삭제)
     */
    override fun setItem(
        key: String,
        value: String?,
    ) {
        // SharedPreferences 편집기를 사용하여 값 변경
        prefs.edit().apply {
            if (value == null) {
                // 값이 null이면 해당 키를 완전히 제거
                remove(key)
            } else {
                // 값이 있으면 문자열로 저장
                putString(key, value)
            }
            // 변경사항을 비동기적으로 디스크에 저장
            // commit() 대신 apply()를 사용하여 UI 스레드 블로킹 방지
            apply()
        }
    }
}
