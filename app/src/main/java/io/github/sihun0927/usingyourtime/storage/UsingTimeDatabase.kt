package io.github.sihun0927.usingyourtime.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 기기 안 세션 기록(스펙 8절). 테이블은 `sessions` 하나뿐이다.
 *
 * 스키마를 파일로 내보내지 않는다. 아직 출시 전이라 마이그레이션 대상 설치본이 없고, 첫 릴리스
 * 뒤 스키마를 고칠 때 함께 켠다.
 */
@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
@TypeConverters(SessionEndReasonConverter::class)
abstract class UsingTimeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        private const val NAME = "usingtime"

        @Volatile
        private var instance: UsingTimeDatabase? = null

        /** 프로세스마다 하나. 서비스와 액티비티가 같은 인스턴스를 쓴다(DI 없이 수동 조립). */
        fun get(context: Context): UsingTimeDatabase = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, UsingTimeDatabase::class.java, NAME)
                .build()
                .also { instance = it }
        }
    }
}
