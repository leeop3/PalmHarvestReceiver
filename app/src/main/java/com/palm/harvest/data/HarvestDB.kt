package com.palm.harvest.data
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

data class HarvesterSummary(
    val harvesterId: String,
    val totalRipe: Int,
    val totalEmpty: Int,
    val reportCount: Int
)

@Dao
interface HarvestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(report: HarvestReport)

    @Query("SELECT * FROM harvest_reports ORDER BY timestamp DESC")
    fun getAllReports(): Flow<List<HarvestReport>>

    @Query("""
        SELECT harvesterId, 
               SUM(ripeBunches) as totalRipe, 
               SUM(emptyBunches) as totalEmpty, 
               COUNT(id) as reportCount 
        FROM harvest_reports 
        GROUP BY harvesterId
    """)
    fun getSummaries(): Flow<List<HarvesterSummary>>
}

// FIX: Set exportSchema = false to remove the Kapt warning
@Database(entities = [HarvestReport::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao
}