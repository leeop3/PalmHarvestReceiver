package com.palm.harvest.data
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM harvest_reports ORDER BY timestamp DESC")
    fun getAllReports(): Flow<List<HarvestReport>>

    @Query("SELECT blockId, SUM(ripeBunches) as totalRipe, SUM(emptyBunches) as totalEmpty, SUM(ripeBunches + emptyBunches) as totalBunches FROM harvest_reports GROUP BY blockId ORDER BY blockId ASC")
    fun getBlockSummaries(): Flow<List<BlockSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: DiscoveredNode)

    @Query("SELECT * FROM discovered_nodes ORDER BY lastHeard DESC")
    fun getAllNodes(): Flow<List<DiscoveredNode>>
}

@Database(entities = [HarvestReport::class, DiscoveredNode::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "harvest-db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}