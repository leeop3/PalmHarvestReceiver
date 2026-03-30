package com.palm.harvest.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(tableName = "harvest_reports")
data class HarvestReport(
    @PrimaryKey val id: String,
    val harvesterId: String,
    val blockId: String,
    val ripeBunches: Int,
    val emptyBunches: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val photoFile: String
)

@Entity(tableName = "discovered_nodes")
data class DiscoveredNode(
    @PrimaryKey val hash: String,
    val nickname: String,
    val lastHeard: Long
)

data class BlockSummary(
    val blockId: String,
    val totalRipe: Int,
    val totalEmpty: Int,
    val totalBunches: Int
)

@Dao
interface HarvestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(report: HarvestReport)

    // CHANGED TO LiveData to prevent Fragment crashes
    @Query("SELECT * FROM harvest_reports ORDER BY timestamp DESC")
    fun getAllReports(): LiveData<List<HarvestReport>>

    @Query("""
        SELECT IFNULL(blockId, 'Unknown') as blockId, 
               COALESCE(SUM(ripeBunches), 0) as totalRipe, 
               COALESCE(SUM(emptyBunches), 0) as totalEmpty, 
               COALESCE(SUM(ripeBunches + emptyBunches), 0) as totalBunches 
        FROM harvest_reports 
        GROUP BY blockId 
        ORDER BY blockId ASC
    """)
    fun getBlockSummaries(): LiveData<List<BlockSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: DiscoveredNode)

    @Query("SELECT * FROM discovered_nodes ORDER BY lastHeard DESC")
    fun getAllNodes(): LiveData<List<DiscoveredNode>>
}

@Database(entities = [HarvestReport::class, DiscoveredNode::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao

    // USING THE EXACT SYNCHRONIZED SINGLETON FROM YOUR OTHER APP
    companion object {
        private const val DATABASE_NAME = "harvest_receiver.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}