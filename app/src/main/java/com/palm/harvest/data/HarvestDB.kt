package com.palm.harvest.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(
    tableName = "harvest_records",
    indices = [
        Index(value = ["externalId"], unique = true),
        Index(value = ["harvesterId", "timestamp"], unique = true),
        Index(value = ["harvesterId"]),
        Index(value = ["reportDate"])
    ]
)
data class HarvestRecord(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val externalId: String,
    val harvesterId: String,
    val blockId: String,
    val ripeBunches: Int,
    val emptyBunches: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val reportDate: String,
    val photoFile: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val rawCsv: String = ""
)

@Entity(tableName = "discovered_nodes")
data class DiscoveredNode(
    @PrimaryKey val hash: String,
    val nickname: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val announceCount: Int
) {
    val label: String get() = nickname.takeIf { it.isNotBlank() && it != "Unknown Harvester" } ?: "Node ${hash.take(8)}…"
    val shortAddress: String get() = hash.takeLast(8).chunked(2).joinToString(":")
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
    suspend fun insertReport(report: HarvestRecord)

    @Query("SELECT * FROM harvest_records ORDER BY receivedAt DESC")
    fun getAllReports(): LiveData<List<HarvestRecord>>

    @Query("""
        SELECT blockId, 
               SUM(ripeBunches) as totalRipe, 
               SUM(emptyBunches) as totalEmpty, 
               SUM(ripeBunches + emptyBunches) as totalBunches 
        FROM harvest_records 
        GROUP BY blockId 
        ORDER BY blockId ASC
    """)
    fun getBlockSummaries(): LiveData<List<BlockSummary>>

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

    // FIX: Changed lastHeard to lastSeen to match the Entity
    @Query("SELECT * FROM discovered_nodes ORDER BY lastSeen DESC")
    fun getAllNodes(): LiveData<List<DiscoveredNode>>
}

@Database(entities = [HarvestRecord::class, DiscoveredNode::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao
    companion object {
        private const val DATABASE_NAME = "harvest_receiver.db"
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}