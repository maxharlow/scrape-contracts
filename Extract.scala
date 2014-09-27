import java.io.File
import scala.xml.XML
import scala.collection.immutable.ListMap
import scala.concurrent.Await
import scala.concurrent.duration._
import dispatch._
import dispatch.Defaults._
import com.github.tototoshi.csv.CSVWriter

object Extract extends App {

  val http = Http.configure(_ setFollowRedirects true)

  println("""
    ,---,
    |___|    .        ___ __   _   _  ___  _  __
    /``\ |---^-,_      |  |_) /_\ / `  |  / \ |_)
   ( () )  ( o )||     |  | \ | | \_,  |  \_/ | \
    \__/````\_/  \
  """)

  run()

  def run() {
    val awardsCsv = CSVWriter.open(new File("awards.csv"))
    val awardsHeaders = List(
      "noticeId",
      "noticePublishedDate",
      "noticeType",
      "noticeForm",
      "noticeSystemState",
      "noticeSystemStateChangeDate",
      "noticeDocsNumber",
      "contractTitle",
      "contractDescription",
      "contractLocation",
      "contractClassifications",
      "contractAwardDate",
      "contractAwardValue",
      "contractAwardReference",
      "buyerGroupId",
      "buyerGroupName",
      "buyerOrgName",
      "buyerContact",
      "supplierName",
      "supplierCompanyNumber",
      "supplierContact")
    awardsCsv writeRow awardsHeaders
    for {
      year <- 2011 to 2014
      month <- 1 to 12
    }
    yield for (data <- retrieve(year, month))
    yield for (item <- process(data)) {
      val awards = selectAwards(item)
      awardsCsv writeRow awards.values.toSeq
    }
    awardsCsv.close()
  }

  def retrieve(year: Int, month: Int): Future[String] = {
    println(s"Now retrieving $year-$month...")
    val monthFormatted = "%02d".format(month)
    val response = http {
      url(s"http://www.contractsfinder.businesslink.gov.uk/public_files/Notices/Monthly/notices_${year}_${monthFormatted}.xml") OK as.String
    }
    Await.ready(response, 1.minute)
    response
  }

  def process(data: String): Seq[xml.Node] = {
    for {
      xml <- XML.loadString(data.dropWhile(_ != '<'))
      body <- xml \ "NOTICES" \ "_"
      awards <- body.filter(_.label == "CONTRACT_AWARD")
      award <- awards
    }
    yield awards
  }

  def selectAwards(award: xml.Node): ListMap[String, String] = {
    ListMap(
      "noticeId" -> (award \ "SYSTEM" \ "NOTICE_ID").text,
      "noticePublishedDate" -> (award \ "SYSTEM" \ "SYSTEM_PUBLISHED_DATE").text,
      "noticeType" -> (award \ "SYSTEM" \ "NOTICE_TYPE_FRIENDLY_NAME").text,
      "noticeForm" -> {
        val formNumber = (award \ "@FORM").text.toInt
        if (formNumber == 1 || formNumber == 2 || formNumber == 3) "Below-OJEU"
        else if (formNumber == 90 || formNumber == 91) "Transparency"
        else if (formNumber == 92 || formNumber == 93) "Subcontract" // never seems to occur?
        else "UNKNOWN"
      },
      "noticeSystemState" -> (award \ "SYSTEM" \ "SYSTEM_NOTICE_STATE").text,
      "noticeSystemStateChangeDate" -> (award \ "SYSTEM" \ "SYSTEM_NOTICE_STATE_CHANGE_DATE").text,
      "noticeDocsNumber" -> (award \ "FD_CONTRACT_AWARD" \ "DOCUMENTS" \ "_").length.toString,
      "contractTitle" -> (award \ "FD_CONTRACT_AWARD" \ "OBJECT_CONTRACT_INFORMATION_CONTRACT_AWARD_NOTICE" \ "DESCRIPTION_AWARD_NOTICE_INFORMATION" \ "TITLE_CONTRACT").text.trim,
      "contractDescription" -> (award \ "FD_CONTRACT_AWARD" \ "OBJECT_CONTRACT_INFORMATION_CONTRACT_AWARD_NOTICE" \ "DESCRIPTION_AWARD_NOTICE_INFORMATION" \ "SHORT_CONTRACT_DESCRIPTION").text.trim,
      "contractLocation" -> (award \ "FD_CONTRACT_AWARD" \ "OBJECT_CONTRACT_INFORMATION_CONTRACT_AWARD_NOTICE" \ "DESCRIPTION_AWARD_NOTICE_INFORMATION" \ "LOCATION_NUTS" \\ "p").head.text,
      "contractClassifications" -> (award \ "FD_CONTRACT_AWARD" \ "OBJECT_CONTRACT_INFORMATION_CONTRACT_AWARD_NOTICE" \ "DESCRIPTION_AWARD_NOTICE_INFORMATION" \ "CPV" \\ "CPV_CODE").map(_.\("@CODE").text).mkString(";"),
      "contractAwardDate" -> {
        val datesValues = award \ "FD_CONTRACT_AWARD" \ "AWARD_OF_CONTRACT" \ "CONTRACT_AWARD_DATE"
        val dates = datesValues map { dateValue =>
          if (dateValue.isEmpty) "" else (dateValue \ "YEAR").text + "-" + (dateValue \ "MONTH").text + "-" + (dateValue \ "DAY").text
        }
        dates.mkString(";")
      },
      "contractAwardValue" -> (award \ "FD_CONTRACT_AWARD" \ "OBJECT_CONTRACT_INFORMATION_CONTRACT_AWARD_NOTICE" \ "TOTAL_FINAL_VALUE" \ "COSTS_RANGE_AND_CURRENCY_WITH_VAT_RATE" \ "VALUE_COST").text,
      "contractAwardReference" -> (award \ "FD_CONTRACT_AWARD" \ "PROCEDURE_DEFINITION_CONTRACT_AWARD_NOTICE" \ "ADMINISTRATIVE_INFORMATION_CONTRACT_AWARD" \ "FILE_REFERENCE_NUMBER").text,
      "buyerGroupId" -> (award \ "SYSTEM" \ "BUYER_GROUP_ID").text,
      "buyerGroupName" -> (award \ "SYSTEM" \ "BUYER_GROUP_NAME").text.trim,
      "buyerOrgName" -> (award \ "FD_CONTRACT_AWARD" \ "CONTRACTING_AUTHORITY_INFORMATION" \ "NAME_ADDRESSES_CONTACT_CONTRACT_AWARD" \ "CA_CE_CONCESSIONAIRE_PROFILE" \ "ORGANISATION").text.trim,
      "buyerContact" -> (award \ "FD_CONTRACT_AWARD" \ "CONTRACTING_AUTHORITY_INFORMATION" \ "NAME_ADDRESSES_CONTACT_CONTRACT_AWARD" \ "CA_CE_CONCESSIONAIRE_PROFILE" \ "E_MAIL").text.trim,
      "supplierName" -> (award \ "FD_CONTRACT_AWARD" \ "AWARD_OF_CONTRACT" \ "ECONOMIC_OPERATOR_NAME_ADDRESS" \ "CONTACT_DATA_WITHOUT_RESPONSIBLE_NAME" \ "ORGANISATION").map(_.text.trim).mkString(";"),
      "supplierCompanyNumber" -> (award \ "FD_CONTRACT_AWARD" \ "AWARD_OF_CONTRACT" \ "COMPANIES_HOUSE_URI_SUFFIX").map(_.text).mkString(";"),
      "supplierContact" -> (award \ "FD_CONTRACT_AWARD" \ "AWARD_OF_CONTRACT" \ "ECONOMIC_OPERATOR_NAME_ADDRESS" \ "EMAIL").map(_.text.trim).mkString(";")
    )
  }

}
