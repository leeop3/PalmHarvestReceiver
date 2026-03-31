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
    val firstSeen: Long,
    val lastSeen: Long,
    val announceCount: Int
) {
    val label: String
        get() = nickname.takeIf { it.isNotBlank() && it != "Unknown Harvester" }
            ?: "Node ${hash.take(8)}…"

    val shortAddress: String
        get() = hash.takeLast(8).chunked(2).joinToString(":")
}

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

    // SAFE UPSERT METHOD: Works on ALL Android versions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNode(node: DiscoveredNode)

    @Update
    suspend fun updateNode(node: DiscoveredNode)

    @Query("SELECT * FROM discovered_nodes WHERE hash = :hash LIMIT 1")
    suspend fun getNode(hash: String): DiscoveredNode?

    @Transaction
    suspend fun trackNode(hash: String, nickname: String, time: Long) {
        val existing = getNode(hash)
        if (existing != null) {
            updateNode(existing.copy(nickname = nickname, lastSeen = time, announceCount = existing.announceCount + 1))
        } else {
            insertNode(DiscoveredNode(hash, nickname, time, time, 1))
        }
    }

    @Query("SELECT * FROM discovered_nodes ORDER BY lastSeen DESC")
    fun getAllNodes(): LiveData<List<DiscoveredNode>>
}

// BUMPED TO VERSION 7 to clear out any corrupted tables
@Database(entities =[HarvestReport::class, DiscoveredNode::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao

    companion object {
        private const val DATABASE_NAME = "harvest_receiver.db"
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration().build()
        }
    }
}