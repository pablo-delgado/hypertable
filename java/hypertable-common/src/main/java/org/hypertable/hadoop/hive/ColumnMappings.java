/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This source file is based on code taken from SQLLine 1.0.2
 * See SQLLine notice in LICENSE
 */

package org.hypertable.hadoop.hive;

import org.hypertable.hadoop.hive.Properties;

import com.google.common.collect.Iterators;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ColumnMappings implements Iterable<ColumnMappings.ColumnMapping> {

  private final int keyIndex;
  private final ColumnMapping[] columnsMapping;

  public ColumnMappings(List<ColumnMapping> columnMapping, int keyIndex) {
    this.columnsMapping = columnMapping.toArray(new ColumnMapping[columnMapping.size()]);
    this.keyIndex = keyIndex;
  }

  @Override
  public Iterator<ColumnMapping> iterator() {
    return Iterators.forArray(columnsMapping);
  }

  public int size() {
    return columnsMapping.length;
  }

  String toTypesString() {
    StringBuilder sb = new StringBuilder();
    for (ColumnMapping colMap : columnsMapping) {
      if (sb.length() > 0) {
        sb.append(":");
      }
      if (colMap.isRowKey) {
        // the row key column becomes a STRING
        sb.append(serdeConstants.STRING_TYPE_NAME);
      } else if (colMap.qualifierName == null) {
        // a column family become a MAP
        sb.append(serdeConstants.MAP_TYPE_NAME + "<" + serdeConstants.STRING_TYPE_NAME + ","
            + serdeConstants.STRING_TYPE_NAME + ">");
      } else {
        // an individual column becomes a STRING
        sb.append(serdeConstants.STRING_TYPE_NAME);
      }
    }
    return sb.toString();
  }

  void setHiveColumnDescription(String serdeName,
      List<String> columnNames, List<TypeInfo> columnTypes) throws SerDeException {
    if (columnsMapping.length != columnNames.size()) {
      throw new SerDeException(serdeName + ": columns has " + columnNames.size() +
          " elements while hypertable.columns.mapping has " + columnsMapping.length + " elements" +
          " (counting the key if implicit)");
    }

    // check that the mapping schema is right;
    // check that the "column-family:" is mapped to  Map<key,?>
    // where key extends LazyPrimitive<?, ?> and thus has type Category.PRIMITIVE
    for (int i = 0; i < columnNames.size(); i++) {
      ColumnMapping colMap = columnsMapping[i];
      if (colMap.qualifierName == null && !colMap.isRowKey) {
        TypeInfo typeInfo = columnTypes.get(i);
        if ((typeInfo.getCategory() != ObjectInspector.Category.MAP) ||
            (((MapTypeInfo) typeInfo).getMapKeyTypeInfo().getCategory()
                != ObjectInspector.Category.PRIMITIVE)) {

          throw new SerDeException(
              serdeName + ": hypertable column family '" + colMap.familyName
                  + "' should be mapped to Map<? extends LazyPrimitive<?, ?>,?>, that is "
                  + "the Key for the map should be of primitive type, but is mapped to "
                  + typeInfo.getTypeName());
        }
      }
      colMap.columnName = columnNames.get(i);
      colMap.columnType = columnTypes.get(i);
    }
  }

  /**
   * Utility method for parsing a string of the form '-,b,s,-,s:b,...' as a means of specifying
   * whether to use a binary or an UTF string format to serialize and de-serialize primitive
   * data types like boolean, byte, short, int, long, float, and double. This applies to
   * regular columns and also to map column types which are associated with an Hypertable column
   * family. For the map types, we apply the specification to the key or the value provided it
   * is one of the above primitive types. The specifier is a colon separated value of the form
   * -:s, or b:b where we have 's', 'b', or '-' on either side of the colon. 's' is for string
   * format storage, 'b' is for native fixed width byte oriented storage, and '-' uses the
   * table level default.
   *
   * @param hypertableTableDefaultStorageType - the specification associated with the table property
   *        hypertable.table.default.storage.type
   * @throws SerDeException on parse error.
   */
  void parseColumnStorageTypes(String hypertableTableDefaultStorageType) throws SerDeException {

    boolean tableBinaryStorage = false;

    if (hypertableTableDefaultStorageType != null && !"".equals(hypertableTableDefaultStorageType)) {
      if (hypertableTableDefaultStorageType.equals("binary")) {
        tableBinaryStorage = true;
      } else if (!hypertableTableDefaultStorageType.equals("string")) {
        throw new SerDeException("Error: " + Properties.HYPERTABLE_TABLE_DEFAULT_STORAGE_TYPE +
            " parameter must be specified as" +
            " 'string' or 'binary'; '" + hypertableTableDefaultStorageType +
            "' is not a valid specification for this table/serde property.");
      }
    }

    // parse the string to determine column level storage type for primitive types
    // 's' is for variable length string format storage
    // 'b' is for fixed width binary storage of bytes
    // '-' is for table storage type, which defaults to UTF8 string
    // string data is always stored in the default escaped storage format; the data types
    // byte, short, int, long, float, and double have a binary byte oriented storage option
    for (ColumnMapping colMap : columnsMapping) {
      TypeInfo colType = colMap.columnType;
      String mappingSpec = colMap.mappingSpec;
      String[] mapInfo = mappingSpec.split("#");
      String[] storageInfo = null;

      if (mapInfo.length == 2) {
        storageInfo = mapInfo[1].split(":");
      }

      if (storageInfo == null) {

        // use the table default storage specification
        if (colType.getCategory() == ObjectInspector.Category.PRIMITIVE) {
          if (!colType.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else {
            colMap.binaryStorage.add(false);
          }
        } else if (colType.getCategory() == ObjectInspector.Category.MAP) {
          TypeInfo keyTypeInfo = ((MapTypeInfo) colType).getMapKeyTypeInfo();
          TypeInfo valueTypeInfo = ((MapTypeInfo) colType).getMapValueTypeInfo();

          if (keyTypeInfo.getCategory() == ObjectInspector.Category.PRIMITIVE &&
              !keyTypeInfo.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else {
            colMap.binaryStorage.add(false);
          }

          if (valueTypeInfo.getCategory() == ObjectInspector.Category.PRIMITIVE &&
              !valueTypeInfo.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else {
            colMap.binaryStorage.add(false);
          }
        } else {
          colMap.binaryStorage.add(false);
        }

      } else if (storageInfo.length == 1) {
        // we have a storage specification for a primitive column type
        String storageOption = storageInfo[0];

        if ((colType.getCategory() == ObjectInspector.Category.MAP) ||
            !(storageOption.equals("-") || "string".startsWith(storageOption) ||
                "binary".startsWith(storageOption))) {
          throw new SerDeException("Error: A column storage specification is one of the following:"
              + " '-', a prefix of 'string', or a prefix of 'binary'. "
              + storageOption + " is not a valid storage option specification for "
              + colMap.columnName);
        }

        if (colType.getCategory() == ObjectInspector.Category.PRIMITIVE &&
            !colType.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {

          if ("-".equals(storageOption)) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else if ("binary".startsWith(storageOption)) {
            colMap.binaryStorage.add(true);
          } else {
            colMap.binaryStorage.add(false);
          }
        } else {
          colMap.binaryStorage.add(false);
        }

      } else if (storageInfo.length == 2) {
        // we have a storage specification for a map column type

        String keyStorage = storageInfo[0];
        String valStorage = storageInfo[1];

        if ((colType.getCategory() != ObjectInspector.Category.MAP) ||
            !(keyStorage.equals("-") || "string".startsWith(keyStorage) ||
                "binary".startsWith(keyStorage)) ||
            !(valStorage.equals("-") || "string".startsWith(valStorage) ||
                "binary".startsWith(valStorage))) {
          throw new SerDeException("Error: To specify a valid column storage type for a Map"
              + " column, use any two specifiers from '-', a prefix of 'string', "
              + " and a prefix of 'binary' separated by a ':'."
              + " Valid examples are '-:-', 's:b', etc. They specify the storage type for the"
              + " key and value parts of the Map<?,?> respectively."
              + " Invalid storage specification for column "
              + colMap.columnName
              + "; " + storageInfo[0] + ":" + storageInfo[1]);
        }

        TypeInfo keyTypeInfo = ((MapTypeInfo) colType).getMapKeyTypeInfo();
        TypeInfo valueTypeInfo = ((MapTypeInfo) colType).getMapValueTypeInfo();

        if (keyTypeInfo.getCategory() == ObjectInspector.Category.PRIMITIVE &&
            !keyTypeInfo.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {

          if (keyStorage.equals("-")) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else if ("binary".startsWith(keyStorage)) {
            colMap.binaryStorage.add(true);
          } else {
            colMap.binaryStorage.add(false);
          }
        } else {
          colMap.binaryStorage.add(false);
        }

        if (valueTypeInfo.getCategory() == ObjectInspector.Category.PRIMITIVE &&
            !valueTypeInfo.getTypeName().equals(serdeConstants.STRING_TYPE_NAME)) {
          if (valStorage.equals("-")) {
            colMap.binaryStorage.add(tableBinaryStorage);
          } else if ("binary".startsWith(valStorage)) {
            colMap.binaryStorage.add(true);
          } else {
            colMap.binaryStorage.add(false);
          }
        } else {
          colMap.binaryStorage.add(false);
        }

        if (colMap.binaryStorage.size() != 2) {
          throw new SerDeException("Error: In parsing the storage specification for column "
              + colMap.columnName);
        }

      } else {
        // error in storage specification
        throw new SerDeException("Error: " + Properties.HYPERTABLE_COLUMNS_MAPPING + " storage specification "
            + mappingSpec + " is not valid for column: "
            + colMap.columnName);
      }
    }
  }

  public ColumnMapping getKeyMapping() {
    return columnsMapping[keyIndex];
  }

  public int getKeyIndex() {
    return keyIndex;
  }

  public ColumnMapping[] getColumnsMapping() {
    return columnsMapping;
  }

  /**
   * Represents a mapping from a single Hive column to an Hypertable column qualifier, column family or row key.
   */
  // todo use final fields
  public static class ColumnMapping {

    ColumnMapping() {
      binaryStorage = new ArrayList<Boolean>(2);
    }

    String columnName;
    TypeInfo columnType;

    String familyName;
    String qualifierName;
    byte[] familyNameBytes;
    byte[] qualifierNameBytes;
    List<Boolean> binaryStorage;
    boolean isRowKey;
    String mappingSpec;

    public String getColumnName() {
      return columnName;
    }

    public TypeInfo getColumnType() {
      return columnType;
    }

    public String getFamilyName() {
      return familyName;
    }

    public String getQualifierName() {
      return qualifierName;
    }

    public byte[] getFamilyNameBytes() {
      return familyNameBytes;
    }

    public byte[] getQualifierNameBytes() {
      return qualifierNameBytes;
    }

    public List<Boolean> getBinaryStorage() {
      return binaryStorage;
    }

    public boolean isRowKey() {
      return isRowKey;
    }

    public String getMappingSpec() {
      return mappingSpec;
    }

    public boolean isCategory(ObjectInspector.Category category) {
      return columnType.getCategory() == category;
    }
  }

  /**
   * Parses the Hypertable columns mapping specifier to identify the column families, qualifiers
   * and also caches the byte arrays corresponding to them. One of the Hive table
   * columns maps to the Hypertable row key, by default the first column.
   *
   * @param columnsMappingSpec string hypertable.columns.mapping specified when creating table
   * @param doColumnRegexMatching whether to do a regex matching on the columns or not
   * @return List<ColumnMapping> which contains the column mapping information by position
   * @throws org.apache.hadoop.hive.serde2.SerDeException
   */
  public static ColumnMappings parseColumnsMapping(String columnsMappingSpec) throws SerDeException {

    try {

      if (columnsMappingSpec == null) {
        throw new SerDeException("Error: hypertable.columns.mapping missing for this Hypertable table.");
      }

      if (columnsMappingSpec.isEmpty() || columnsMappingSpec.equals(Properties.HYPERTABLE_KEY_COL)) {
        throw new SerDeException("Error: hypertable.columns.mapping specifies only the Hypertable table"
                                 + " row key. A valid Hive-Hypertable table must specify at least one additional column.");
      }

      int rowKeyIndex = -1;
      List<ColumnMapping> columnsMapping = new ArrayList<ColumnMapping>();
      String[] columnSpecs = columnsMappingSpec.split(",");

      for (int i = 0; i < columnSpecs.length; i++) {
        String mappingSpec = columnSpecs[i].trim();
        String [] mapInfo = mappingSpec.split("#");
        String colInfo = mapInfo[0];

        int idxFirst = colInfo.indexOf(":");
        int idxLast = colInfo.lastIndexOf(":");

        if (idxFirst < 0 || !(idxFirst == idxLast)) {
          throw new SerDeException("Error: the Hypertable columns mapping contains a badly formed " +
                                   "column family, column qualifier specification.");
        }

        ColumnMapping columnMapping = new ColumnMapping();

        if (colInfo.equals(Properties.HYPERTABLE_KEY_COL)) {
          rowKeyIndex = i;
          columnMapping.familyName = colInfo;
          columnMapping.familyNameBytes = colInfo.getBytes("UTF-8");
          columnMapping.qualifierName = null;
          columnMapping.qualifierNameBytes = null;
          columnMapping.isRowKey = true;
        } else {
          String [] parts = colInfo.split(":");
          assert(parts.length > 0 && parts.length <= 2);
          columnMapping.familyName = parts[0];
          columnMapping.familyNameBytes = parts[0].getBytes("UTF-8");
          columnMapping.isRowKey = false;

          if (parts.length == 2) {

            // set the regular provided qualifier names
            columnMapping.qualifierName = parts[1];
            columnMapping.qualifierNameBytes = parts[1].getBytes("UTF-8");
          } else {
            columnMapping.qualifierName = null;
            columnMapping.qualifierNameBytes = null;
          }
        }

        columnMapping.mappingSpec = mappingSpec;

        columnsMapping.add(columnMapping);
      }

      if (rowKeyIndex == -1) {
        rowKeyIndex = 0;
        ColumnMapping columnMapping = new ColumnMapping();
        columnMapping.familyName = Properties.HYPERTABLE_KEY_COL;
        columnMapping.familyNameBytes = Properties.HYPERTABLE_KEY_COL.getBytes("UTF-8");
        columnMapping.qualifierName = null;
        columnMapping.qualifierNameBytes = null;
        columnMapping.isRowKey = true;
        columnMapping.mappingSpec = Properties.HYPERTABLE_KEY_COL;
        columnsMapping.add(0, columnMapping);
      }
      return new ColumnMappings(columnsMapping, rowKeyIndex);
    }
    catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      System.exit(1);
    }
    return null;
  }

}
