package codurance.com.timesheet.reminder;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;

import static java.lang.System.*;
import static java.time.DayOfWeek.MONDAY;
import static java.time.LocalDate.now;
import static java.time.Period.ofDays;
import static java.time.Period.ofWeeks;
import static java.time.temporal.TemporalAdjusters.previous;

public class CalendarAnalyser {
  private Connection connection;
  private SsmClient ssm = SsmClient.create();

  public void launch() {
    out.println("Start of launch method");
    try {
      init();
      process();

    } catch (SQLException throwables) {
      throwables.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  private void init() {
    var env = getenv("env");
    String dbUrl, dbUser, dbPassword;
    if (env != null && env.equals("aws")) {
      dbUrl = getSsmParam("/timesheet-gaps/db/url");
      dbUser = getSsmParam("/timesheet-gaps/db/user");
      dbPassword = getSsmParam("/timesheet-gaps/db/password");
    } else {
      dbUrl = getProperty("dbUrl");
      dbUser = getProperty("dbUser");
      dbPassword = getProperty("dbPassword");
    }

    try {
      connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

    } catch (SQLException e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

  private void process() throws Exception {
    var startDate = now().minus(ofWeeks(1)).with(previous(MONDAY));
    var endDate = startDate.plus(ofDays(5));
    out.println(startDate + " " + endDate);

    PreparedStatement timeEntriesForDates = connection.prepareStatement("select * from timeentries where activitydate between '" + startDate + "' and '" + endDate + "'");
    ResultSet resultSet = timeEntriesForDates.executeQuery();
    while (resultSet.next()){
      out.println(resultSet.getString("userid"));
    }
    writeFileUsingPOI();
    writeToS3();
  }

  private void cleanup() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IllegalStateException(e);

    }
  }

  private String getSsmParam(String key) {
    return ssm.getParameter(GetParameterRequest.builder().name(key).withDecryption(true).build()).parameter()
        .value();
  }

  public void writeFileUsingPOI() throws IOException
  {
    //create blank workbook
    XSSFWorkbook workbook = new XSSFWorkbook();

    //Create a blank sheet
    XSSFSheet sheet = workbook.createSheet("Country");

    ArrayList<Object[]> data=new ArrayList<Object[]>();
    data.add(new String[]{"Country","Capital","Population"});
    data.add(new Object[]{"India","Delhi",10000});
    data.add(new Object[]{"France","Paris",40000});
    data.add(new Object[]{"Germany","Berlin",20000});
    data.add(new Object[]{"England","London",30000});


    //Iterate over data and write to sheet
    int rownum = 0;
    for (Object[] countries : data)
    {
      Row row = sheet.createRow(rownum++);

      int cellnum = 0;
      for (Object obj : countries)
      {
        Cell cell = row.createCell(cellnum++);
        if(obj instanceof String)
          cell.setCellValue((String)obj);
        else if(obj instanceof Double)
          cell.setCellValue((Double)obj);
        else if(obj instanceof Integer)
          cell.setCellValue((Integer)obj);
      }
    }
    try
    {
      //Write the workbook in file system
      FileOutputStream out = new FileOutputStream(new File("employeeDetails.xlsx"));
      workbook.write(out);
      out.close();
      System.out.println("CountriesDetails.xlsx has been created successfully");
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
    finally {
      workbook.close();
    }
  }

  private void writeToS3() throws Exception {
    Regions clientRegion = Regions.EU_WEST_1;
    String bucketName = "timesheet-entries-memento";
    String keyName = "employee_details";

    try {
      AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
          .withRegion(clientRegion)
          .withCredentials(new ProfileCredentialsProvider())
          .build();
      TransferManager tm = TransferManagerBuilder.standard()
          .withS3Client(s3Client)
          .build();

      // TransferManager processes all transfers asynchronously,
      // so this call returns immediately.
      Upload upload = tm.upload(bucketName, keyName, new File("employeeDetails.xlsx"));
      System.out.println("Object upload started");

      // Optionally, wait for the upload to finish before continuing.
      upload.waitForCompletion();
      System.out.println("Object upload complete");
    } catch (AmazonServiceException e) {
      // The call was transmitted successfully, but Amazon S3 couldn't process
      // it, so it returned an error response.
      e.printStackTrace();
    } catch (SdkClientException e) {
      // Amazon S3 couldn't be contacted for a response, or the client
      // couldn't parse the response from Amazon S3.
      e.printStackTrace();
    }
  }
}
