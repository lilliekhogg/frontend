package jobs

import app.LifecycleComponent
import com.gu.Box
import common.{AkkaAsync, JobScheduler, Logging}
import dfp.DfpApi
import play.api.inject.ApplicationLifecycle

import scala.concurrent.{ExecutionContext, Future}

object CommercialDfpReporting {

  case class DfpReportRow(value: String)

  private val dfpReports = Box[Map[Long, Seq[DfpReportRow]]](Map.empty)

  val todaysTeamKPIReport    = "All ab-test impressions and CPM"
  val last7DaysTeamKPIReport = "All ab-test impressions and CPM"

  // These IDs correspond to queries saved in DFP's web console.
  val reportMappings = Map(
    todaysTeamKPIReport    -> 10060521970L, // This report is accessible by the DFP user: "NGW DFP Production"
    last7DaysTeamKPIReport -> 10089856977L
  )

  def update(dfpApi: DfpApi)(implicit executionContext: ExecutionContext): Future[Unit] = Future {
    for {
      (reportName, reportId) <- reportMappings.toSeq
    } {
      val maybeReport: Option[Seq[DfpReportRow]] = dfpApi.getReportQuery(reportId)
        .map(reportId => {
          // exclude the CSV header
          dfpApi.runReportJob(reportId).tail.map(DfpReportRow)
        })

      maybeReport.foreach { report: Seq[DfpReportRow] =>
        dfpReports.send( currentMap => {
          currentMap + (reportId -> report)
        })
      }
    }
  }

  def getReport(reportId: Long): Option[Seq[DfpReportRow]] = dfpReports.get().get(reportId)
}

class CommercialDfpReportingLifecycle(
  appLifecycle: ApplicationLifecycle,
  jobs: JobScheduler,
  akkaAsync: AkkaAsync,
  dfpApi: DfpApi)(implicit ec: ExecutionContext) extends LifecycleComponent with Logging {

  appLifecycle.addStopHook { () => Future {
    jobs.deschedule("CommercialDfpReportingJob")
  }}

  override def start(): Unit = {
    jobs.deschedule("CommercialDfpReportingJob")

    CommercialDfpReporting.update(dfpApi)(ec)

    // 30 minutes between each log write.
    jobs.scheduleEveryNMinutes("CommercialDfpReportingJob", 30) {
      log.logger.info(s"Fetching commercial dfp report from dfp api")
      CommercialDfpReporting.update(dfpApi)(ec)
    }
  }

}
