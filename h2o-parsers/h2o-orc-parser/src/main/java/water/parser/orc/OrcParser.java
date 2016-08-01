package water.parser.orc;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.*;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.hadoop.hive.ql.io.orc.RecordReader;
import org.apache.hadoop.hive.ql.io.orc.StripeInformation;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.*;
import org.joda.time.DateTime;
import org.joda.time.MutableDateTime;
import water.H2O;
import water.Job;
import water.Key;
import water.parser.*;
import water.util.ArrayUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static water.parser.orc.OrcUtil.isSupportedSchema;
import static water.parser.orc.OrcUtil.schemaToColumnType;

// Orc support

/**
 * ORC parser for H2O distributed parsing subsystem.
 *
 * Basically, here is the plan:
 * To parse an Orc file, we need to do the following in order to get the following useful
 * information:
 * 1. Get a Reader rdr.
 * 2. From the reader rdr, we can get the following pieces of information:
 *  a. number of columns, column types and column names.  We only support parsing of primitive types;
 *  b. Lists of StripeInformation that describes how many stripes of data that we will need to read;
 *  c. For each stripe, get information like rows per stripe, data size in bytes
 * 3.  The plan is to read the file in parallel in whole numbers of stripes.
 * 4.  Inside each stripe, we will read data out in batches of VectorizedRowBatch (1024 rows or less).
 *
 */
public class OrcParser extends Parser {

  /** Orc Info */
  private final Reader orcFileReader; // can generate all the other fields from this reader
  private BufferedString bs = new BufferedString();
  public static final int DAY_TO_MS = 24*3600*1000;
  public static final int ADD_OFFSET = 8*3600*1000;
  public static final int HOUR_OFFSET = 3600000;  // in ms to offset for leap seconds, years
  private MutableDateTime epoch = new MutableDateTime();  // used to help us out the leap seconds, years
  private ArrayList<String> storeWarnings = new ArrayList<String>();  // store a list of warnings


  OrcParser(ParseSetup setup, Key<Job> jobKey) {
    super(setup, jobKey);

    epoch.setDate(0);   // used to figure out leap seconds, years

    this.orcFileReader = ((OrcParser.OrcParseSetup) setup).orcFileReader;
  }

  /**
   * This method calculates the number of stripes that will be read for each chunk.  Since
   * only single threading is supported in reading each stripe, we will never split one stripe
   * over different chunks.
   *
   * @param chunkId: chunk index, calculated as file size/chunk size.  The file size is calculated
   *            with data plus overhead in terms of headers and other info, number of chunks
   *            calculated will be higher than the actual chunks needed.  If the chunk number
   *            is too high, the method will return without writing to
   *            dout.
   * @param din: ParseReader, not used for parsing orc files
   * @param dout: ParseWriter, used to add data to H2O frame.
     * @return: Parsewriter dout.
     */
  @Override
  protected final ParseWriter parseChunk(int chunkId, ParseReader din, ParseWriter dout) {
    // only do something if within file size and the orc file is not empty
    List<StripeInformation> stripesInfo = ((OrcParseSetup) this._setup).getOrcFileReader().getStripes();
    if(stripesInfo.size() == 0) return dout; // empty file
    OrcParseSetup setup = (OrcParseSetup) this._setup;
    StripeInformation thisStripe = stripesInfo.get(chunkId);  // get one stripe
    // write one stripe of data to H2O frame
    final String [] columnNames = setup.getColumnNames();
    String [] orcTypes = setup.getColumnTypesString();
    boolean[] toInclude = setup.getToInclude();

    try {
      RecordReader perStripe = orcFileReader.rows(thisStripe.getOffset(), thisStripe.getDataLength(),
              setup.getToInclude(), null, setup.getColumnNames());
      VectorizedRowBatch batch = perStripe.nextBatch(null);  // read orc file stripes in vectorizedRowBatch

      long numCols = batch.numCols;
      boolean done = false;
      long rowCounts = 0L;
      long rowNumber = thisStripe.getNumberOfRows();
      while (!done) {
        long currentBatchRow = batch.count();
        ColumnVector[] dataVectors = batch.cols;

        int colIndex = 0;
        for (int col = 0; col < numCols; ++col) {  // read one column at a time;
          if (toInclude[col+1]) {// only write a column if we actually wants it
            write1column(dataVectors[col], orcTypes[colIndex], colIndex, currentBatchRow, chunkId, din, dout);
            colIndex++;
          }
        }

        rowCounts = rowCounts + currentBatchRow;    // record number of rows of data actually read
        if (rowCounts >= rowNumber)               // read all rows of the stripe already.
          done = true;
        if (!done)  // not done yet, get next batch
          batch = perStripe.nextBatch(batch);
      }
      assert rowCounts == rowNumber:"rowCounts = " + rowCounts + ", rowNumber = " + rowNumber;
      perStripe.close();
    } catch(IOException ioe) {
      throw new RuntimeException(ioe);
    }
    return dout;
  }


  /**
   * This method writes one column of H2O data frame at a time.
   *
   * @param oneColumn
   * @param columnType
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void write1column(ColumnVector oneColumn, String columnType, int cIdx, Long rowNumber, int chunkIdx,
                            ParseReader din, ParseWriter dout) {
    try {
      switch (columnType.toLowerCase()) {
        case "bigint":
        case "boolean":
        case "int":
        case "smallint":
        case "tinyint":
          writeLongcolumn(oneColumn, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, chunkIdx, dout);
          break;
        case "float":
        case "real":    // type used by h2o
        case "double":
          writeDoublecolumn(oneColumn, columnType, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, dout);
          break;
        case "numeric":
          if (oneColumn.getClass().getName().contains("Long"))
            writeLongcolumn(oneColumn, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, chunkIdx, dout);
          else
            writeDoublecolumn(oneColumn, columnType, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, dout);
          break;
        case "string":
        case "varchar":
        case "char":
//        case "binary":  //FIXME: only reading it as string right now.
          writeStringcolumn(oneColumn, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, dout);
          break;
        case "enum":
          writeEnumColumn(oneColumn, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, chunkIdx, dout);
          break;
        case "date":
        case "timestamp":
          writeTimecolumn(oneColumn, columnType, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, dout);
          break;
        case "decimal":
          writeDecimalcolumn(oneColumn, oneColumn.noNulls, oneColumn.isNull, cIdx, rowNumber, dout);
          break;
        default:
          throw new IllegalArgumentException("Unsupported Orc schema type: " + columnType);
      }
    } catch(Throwable t ) {
      t.printStackTrace();
    }
  }

  /**
   * This method is written to write a column of enums to the frame.  However, enums can be a number
   * or a string.  Hence, we break this one out and do it on its own
   *
   * @param oneEnumColumn
   * @param noNull
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeEnumColumn(ColumnVector oneEnumColumn, boolean noNull, boolean[] isNull, int cIdx, Long rowNumber,
                               int chunkIdx, ParseWriter dout) {

    String orcColumnType = oneEnumColumn.getClass().getName().toLowerCase();
    if (orcColumnType.contains("long")) {  // a numeric categorical
      long[] oneColumn = ((LongColumnVector) oneEnumColumn).vector;

      if (noNull) {
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
          dout.addStrCol(cIdx, bs.set(Long.toString(oneColumn[rowIndex])));
          check_Max_Value(oneColumn[rowIndex], cIdx, rowNumber, chunkIdx, dout);
        }
      } else {
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
          if (isNull[rowIndex])
            dout.addInvalidCol(cIdx);
          else {
            dout.addStrCol(cIdx, bs.set(Long.toString(oneColumn[rowIndex])));
            check_Max_Value(oneColumn[rowIndex], cIdx, rowNumber, chunkIdx, dout);
          }
        }
      }
    } else if (orcColumnType.contains("bytes")) { // for char, varchar, string
      byte[][] oneColumn  = ((BytesColumnVector) oneEnumColumn).vector;
      int[] stringLength = ((BytesColumnVector) oneEnumColumn).length;
      int[] stringStart = ((BytesColumnVector) oneEnumColumn).start;

      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        if (isNull[rowIndex])
          dout.addInvalidCol(cIdx);
        else {
          if (stringLength[rowIndex] == 0) {  // string value same as last one, no need to set buffer bs
            dout.addStrCol(cIdx, bs);
          } else
            dout.addStrCol(cIdx, bs.set(oneColumn[rowIndex],stringStart[rowIndex],stringLength[rowIndex]));
        }
      }
    } else if (orcColumnType.contains("double")) {  // for double and floats
      double[] oneColumn = ((DoubleColumnVector) oneEnumColumn).vector;

      if (noNull) {
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++)
          dout.addStrCol(cIdx, bs.set(String.valueOf(oneColumn[rowIndex])));
      } else {
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
          if (isNull[rowIndex])
            dout.addInvalidCol(cIdx);
          else
            dout.addStrCol(cIdx, bs.set(String.valueOf(oneColumn[rowIndex])));
        }
      }
    } else
      throw new IllegalArgumentException("Cannot change the following type to enum: " + orcColumnType);
  }


  /**
   * This method is written to take care of the leap seconds, leap year effects.  Our original
   * plan of converting number of days from epoch does not quite work out right due to all these
   * leap seconds, years accumulated over the century.  However, I do notice that when we are
   * not correcting for the leap seconds/years, if we build a dateTime object, the hour does not
   * work out to be 00.  Instead it is off.  In this case, we just calculate the offset and take
   * if off our straight forward timestamp calculation.
   *
   * @param daysSinceEpoch: number of days since epoch (1970 1/1)
   * @return long: correct timestamp corresponding to daysSinceEpoch
     */
  private long correctTimeStamp(long daysSinceEpoch) {
    long timestamp = (daysSinceEpoch*DAY_TO_MS+ADD_OFFSET);

    DateTime date = new DateTime(timestamp);

    int hour = date.hourOfDay().get();

    if (hour == 0)
      return timestamp;
    else
      return (timestamp-hour*HOUR_OFFSET);
  }

  /**
   * This method writes one column of H2O frame for column type timestamp.  This is just a long that
   * records the number of seconds since Jan 1, 2015.
   *
   * @param oneTSColumn
   * @param noNulls
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeTimecolumn(ColumnVector oneTSColumn, String columnType, boolean noNulls, boolean[] isNull, int cIdx,
                                      Long rowNumber, ParseWriter dout) {
    long[] oneColumn = ((LongColumnVector) oneTSColumn).vector;

    if (noNulls) {
      switch (columnType) {
        case "timestamp":
          for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
            dout.addNumCol(cIdx, oneColumn[rowIndex]/1000000);
          }
          break;
        default:
          for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
            dout.addNumCol(cIdx, correctTimeStamp(oneColumn[rowIndex]));
          }
        }

    } else {
      if (columnType.contains("timestamp")) {
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
          if (isNull[rowIndex])
            dout.addInvalidCol(cIdx);
          else
                dout.addNumCol(cIdx, oneColumn[rowIndex] / 1000000);
        }
      } else {  // date
        for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
          if (isNull[rowIndex])
            dout.addInvalidCol(cIdx);
          else {
            dout.addNumCol(cIdx, correctTimeStamp(oneColumn[rowIndex]));
          }
        }
      }
    }
  }

  /**
   * This method writes a column to H2O frame for column type Decimal.  It is just written as some
   * integer without using the scale field.  Need to make sure this is what the customer wants.
   *
   * @param oneDecimalColumn
   * @param noNulls
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeDecimalcolumn(ColumnVector oneDecimalColumn, boolean noNulls, boolean[] isNull, int cIdx,
                                         Long rowNumber, ParseWriter dout) {
    HiveDecimalWritable[] oneColumn = ((DecimalColumnVector) oneDecimalColumn).vector;
    for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
      HiveDecimal hd = oneColumn[rowIndex].getHiveDecimal();
      if(noNulls || !isNull[rowIndex])
        dout.addNumCol(cIdx, hd.unscaledValue().longValue(),-hd.scale());
    }
  }

  /**
   * This method writes a column of H2O frame for Orc File column types of string, varchar, char and
   * binary at some point.
   *
   * @param oneStringColumn
   * @param noNulls
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeStringcolumn(ColumnVector oneStringColumn, boolean noNulls,
                                        boolean[] isNull, int cIdx, Long rowNumber, ParseWriter dout) {

    byte[][] oneColumn  = ((BytesColumnVector) oneStringColumn).vector;
    int[] stringLength = ((BytesColumnVector) oneStringColumn).length;
    int[] stringStart = ((BytesColumnVector) oneStringColumn).start;

    if (noNulls) {
      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        if (stringLength[rowIndex] == 0) {  // string value same as last one, no need to set buffer bs
          dout.addStrCol(cIdx, bs);
        } else
          dout.addStrCol(cIdx, bs.set(oneColumn[rowIndex], stringStart[rowIndex], stringLength[rowIndex]));
      }
    } else {
      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        if (isNull[rowIndex])
          dout.addInvalidCol(cIdx);
        else {
          if (stringLength[rowIndex] == 0) {  // string value same as last one, no need to set buffer bs
            dout.addStrCol(cIdx, bs);
          } else
            dout.addStrCol(cIdx, bs.set(oneColumn[rowIndex], stringStart[rowIndex], stringLength[rowIndex]));
        }
      }
    }
  }


  /**
   * This method writes a column of H2O frame for Orc File column type of float or double.
   *
   * @param oneDoubleColumn
   * @param columnType
   * @param noNulls
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeDoublecolumn(ColumnVector oneDoubleColumn, String columnType, boolean noNulls,
                                        boolean[] isNull, int cIdx, Long rowNumber, ParseWriter dout) {
    double[] oneColumn = ((DoubleColumnVector) oneDoubleColumn).vector;

    if (noNulls) {
        switch (columnType) {
          case "float":
            for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++)
              dout.addNumCol(cIdx, (float) oneColumn[rowIndex]);
            break;
          case "double":
            for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++)
              dout.addNumCol(cIdx, oneColumn[rowIndex]);
        }
    } else {
      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        if (isNull[rowIndex])
          dout.addInvalidCol(cIdx);
        else {
          switch (columnType) {
            case "float":
              dout.addNumCol(cIdx, (float) oneColumn[rowIndex]);
              break;
            case "double":
              dout.addNumCol(cIdx, oneColumn[rowIndex]);
          }
        }
      }
    }
  }

  /**
   * This method writes a column of H2O frame for Orc File column type of boolean, bigint, int, smallint,
   * tinyint and date.
   *
   * @param oneLongColumn
   * @param noNull
   * @param isNull
   * @param cIdx
   * @param rowNumber
     * @param dout
     */
  private void writeLongcolumn(ColumnVector oneLongColumn, boolean noNull, boolean[] isNull, int cIdx, Long rowNumber,
                               int chunkIdx, ParseWriter dout) {
    long[] oneColumn = ((LongColumnVector) oneLongColumn).vector;

//    oneColumn[0] = Long.MAX_VALUE;  // for DEBUG

    if (noNull) {
      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        dout.addNumCol(cIdx, oneColumn[rowIndex], 0);
        check_Max_Value(oneColumn[rowIndex], cIdx, rowNumber, chunkIdx, dout);
      }
    } else {
      for (int rowIndex = 0; rowIndex < rowNumber; rowIndex++) {
        if (isNull[rowIndex])
          dout.addInvalidCol(cIdx);
        else {
          dout.addNumCol(cIdx, oneColumn[rowIndex], 0);
          check_Max_Value(oneColumn[rowIndex], cIdx, rowNumber, chunkIdx, dout);
        }
      }
    }
  }

  /**
   * This method is written to check and make sure any value written to a column of type long
   * is less than Long.MAX_VALUE.  If this is not true, a warning will be passed to the user.
   *
   * @param l
   * @param cIdx
   * @param rowNumber
   * @param chunkIdx
   * @param dout
     */
  private void check_Max_Value(long l, int cIdx, Long rowNumber, int chunkIdx, ParseWriter dout) {
    if (l >= Long.MAX_VALUE) {
      String warning = " Long.MAX_VALUE: " + l + " is found in column "+cIdx+" row "+rowNumber +
              " of stripe "+chunkIdx +".  This value is used for sentinel and will not be parsed correctly.";

      dout.addError(new ParseWriter.ParseErr(warning, chunkIdx, rowNumber, -2L));

    }
  }

  public static class OrcParseSetup extends ParseSetup {
    // expand to include Orc specific fields
    transient Reader orcFileReader;
    String[] columnTypesString;
    boolean[] toInclude;
    String[] allColumnNames;

    public OrcParseSetup(int ncols,
                         String[] columnNames,
                         byte[] ctypes,
                         String[][] domains,
                         String[][] naStrings,
                         String[][] data,
                         Reader orcReader,
                         String[] columntypes,
                         boolean[] toInclude,
                         String[] allColNames) {
      super(OrcParserProvider.ORC_INFO, (byte) '|', true, HAS_HEADER ,
              ncols, columnNames, ctypes, domains, naStrings, data);
      this.orcFileReader = orcReader;
      this.columnTypesString = columntypes;
      this.toInclude = toInclude;
      this.allColumnNames = allColNames;
    }


    @Override
    protected Parser parser(Key jobKey) {
      return new OrcParser(this, jobKey);
    }

    public Reader getOrcFileReader() {
      return this.orcFileReader;
    }

    public String[] getColumnTypesString() {
      return this.columnTypesString;
    }

    public void setColumnTypeStrings(String[] columnTypeStrings) {
      this.columnTypesString = columnTypeStrings;
    }

    public boolean[] getToInclude() { return this.toInclude; }
    public String[] getAllColNames() { return this.allColumnNames; }
    public void setAllColNames(String[] columnNames) {
      this.allColumnNames = allColumnNames;
    }

    public void setOrcFileReader(Reader orcFileReader) {
      this.orcFileReader = orcFileReader;
    }
  }

  // types are flattened in pre-order tree walk, here we just count the number of fields for non-primitve types
  // which are ignored for now
  static private int countStructFields(ObjectInspector x, ArrayList<String> allColumnNames) {
    int res = 1;
    switch(x.getCategory()) {
      case STRUCT:
        StructObjectInspector structObjectInspector = (StructObjectInspector) x;
        List<StructField> allColumns = (List<StructField>) structObjectInspector.getAllStructFieldRefs(); // column info
        for (StructField oneField : allColumns) {
          allColumnNames.add(oneField.getFieldName());
          res += countStructFields(oneField.getFieldObjectInspector(),allColumnNames);
        }
        break;
      case LIST:
        ListObjectInspector listObjectInspector = (ListObjectInspector) x;
        allColumnNames.add("list");
        res += countStructFields(listObjectInspector.getListElementObjectInspector(),allColumnNames);
        break;
      case MAP:
        MapObjectInspector mapObjectInspector = (MapObjectInspector) x;
        allColumnNames.add("mapKey");
        res += countStructFields(mapObjectInspector.getMapKeyObjectInspector(),allColumnNames);
        allColumnNames.add("mapValue");
        res += countStructFields(mapObjectInspector.getMapValueObjectInspector(),allColumnNames);
        break;
      case UNION:
        UnionObjectInspector unionObjectInspector = (UnionObjectInspector)x;
        allColumnNames.add("union");
        for( ObjectInspector xx:unionObjectInspector.getObjectInspectors())
          res += countStructFields(xx,allColumnNames);
        break;
      case PRIMITIVE:break;
      default: throw H2O.unimpl();
    }
    return res;
  }
  /*
   * This function will derive information like column names, types and number from
   * the inspector.
   */
  static OrcParseSetup deriveParseSetup(Reader orcFileReader, StructObjectInspector insp) {
    List<StructField> allColumns = (List<StructField>) insp.getAllStructFieldRefs();  // grab column info
    List<StripeInformation> allStripes = orcFileReader.getStripes();  // grab stripe information
    ArrayList<String> allColNames = new ArrayList<>();
    boolean[] toInclude = new boolean[allColumns.size()+1];
    int supportedFieldCnt = 0 ;
    int colIdx = 0;
    for (StructField oneField:allColumns) {
      allColNames.add(oneField.getFieldName());
      String columnType = oneField.getFieldObjectInspector().getTypeName();
      if (columnType.toLowerCase().contains("decimal")) {
        columnType = "decimal";
      }
      if (isSupportedSchema(columnType)) {
        toInclude[colIdx+1] = true;
        supportedFieldCnt++;
      }
      int cnt = countStructFields(oneField.getFieldObjectInspector(),allColNames);
      if(cnt > 1)
        toInclude = Arrays.copyOf(toInclude,toInclude.length + cnt-1);
      colIdx+=cnt;
    }
    String [] allNames = allColNames.toArray(new String[allColNames.size()]);
    String[] names = new String[supportedFieldCnt];

    byte[] types = new byte[supportedFieldCnt];
    String[][] domains = new String[supportedFieldCnt][];
    String[] dataPreview = new String[supportedFieldCnt];
    String[] dataTypes = new String[supportedFieldCnt];
    ArrayList<String> warnings = new ArrayList<String>();

    // go through all column information
    int columnIndex = 0;
    for (StructField oneField : allColumns) {
      String columnType = oneField.getFieldObjectInspector().getTypeName();
      if (columnType.toLowerCase().contains("decimal"))
        columnType = "decimal"; // get rid of strange attachment
      if (isSupportedSchema(columnType)) {
        names[columnIndex] = oneField.getFieldName();
        types[columnIndex] = schemaToColumnType(columnType);
        dataTypes[columnIndex] = columnType;
        columnIndex++;
      } else {
        warnings.add("Skipping field: " + oneField.getFieldName() + " because of unsupported type: " + columnType);
      }
    }

    // get size of each stripe
    long[] stripeSizes = new long[allStripes.size()];
    long fileSize = 0L;
    long maxStripeSize = 0L;

    for (int index = 0; index < allStripes.size(); index++) {
      long stripeSize = allStripes.get(index).getDataLength();

      if (stripeSize > maxStripeSize)
        maxStripeSize = stripeSize;

      fileSize = fileSize + stripeSize;
      stripeSizes[index] = fileSize;
    }
    OrcParseSetup ps = new OrcParseSetup(
            supportedFieldCnt,
            names,
            types,
            domains,
            null,
            new String[][] { dataPreview },
            orcFileReader,
            dataTypes,
            toInclude,
            allNames
    );

    if (warnings.size() > 0) {
      for (String warning: warnings) {
        ps._errs =  ArrayUtils.append(ps._errs, new ParseWriter.ParseErr(warning, -1, -1L, -2L));
      }
    }
    return ps;
  }
}
