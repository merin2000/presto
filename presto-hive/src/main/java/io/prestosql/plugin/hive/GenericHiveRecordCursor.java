/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.hadoop.TextLineLengthLimitExceededException;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.io.HiveCharWritable;
import org.apache.hadoop.hive.serde2.io.HiveVarcharWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.BinaryComparable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.RecordReader;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static io.prestosql.plugin.hive.util.HiveUtil.closeWithSuppression;
import static io.prestosql.plugin.hive.util.HiveUtil.getDeserializer;
import static io.prestosql.plugin.hive.util.HiveUtil.getTableObjectInspector;
import static io.prestosql.plugin.hive.util.HiveUtil.isStructuralType;
import static io.prestosql.plugin.hive.util.SerDeUtils.getBlockObject;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.Chars.isCharType;
import static io.prestosql.spi.type.Chars.truncateToLengthAndTrimSpaces;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.Decimals.rescale;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static io.prestosql.spi.type.Varchars.truncateToLength;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class GenericHiveRecordCursor<K, V extends Writable>
        implements RecordCursor
{
    private final Path path;
    private final RecordReader<K, V> recordReader;
    private final K key;
    private final V value;

    private final Deserializer deserializer;

    private final Type[] types;
    private final HiveType[] hiveTypes;

    private final StructObjectInspector rowInspector;
    private final ObjectInspector[] fieldInspectors;
    private final StructField[] structFields;

    private final boolean[] loaded;
    private final boolean[] booleans;
    private final long[] longs;
    private final double[] doubles;
    private final Slice[] slices;
    private final Object[] objects;
    private final boolean[] nulls;

    private final long totalBytes;
    private final DateTimeZone hiveStorageTimeZone;

    private long completedBytes;
    private Object rowData;
    private boolean closed;

    public GenericHiveRecordCursor(
            Configuration configuration,
            Path path,
            RecordReader<K, V> recordReader,
            long totalBytes,
            Properties splitSchema,
            List<HiveColumnHandle> columns,
            DateTimeZone hiveStorageTimeZone,
            TypeManager typeManager)
    {
        requireNonNull(path, "path is null");
        requireNonNull(recordReader, "recordReader is null");
        checkArgument(totalBytes >= 0, "totalBytes is negative");
        requireNonNull(splitSchema, "splitSchema is null");
        requireNonNull(columns, "columns is null");
        requireNonNull(hiveStorageTimeZone, "hiveStorageTimeZone is null");

        this.path = path;
        this.recordReader = recordReader;
        this.totalBytes = totalBytes;
        this.key = recordReader.createKey();
        this.value = recordReader.createValue();
        this.hiveStorageTimeZone = hiveStorageTimeZone;

        this.deserializer = getDeserializer(configuration, splitSchema);
        this.rowInspector = getTableObjectInspector(deserializer);

        int size = columns.size();

        this.types = new Type[size];
        this.hiveTypes = new HiveType[size];

        this.structFields = new StructField[size];
        this.fieldInspectors = new ObjectInspector[size];

        this.loaded = new boolean[size];
        this.booleans = new boolean[size];
        this.longs = new long[size];
        this.doubles = new double[size];
        this.slices = new Slice[size];
        this.objects = new Object[size];
        this.nulls = new boolean[size];

        // initialize data columns
        for (int i = 0; i < columns.size(); i++) {
            HiveColumnHandle column = columns.get(i);
            checkState(column.getColumnType() == REGULAR, "column type must be regular");

            types[i] = typeManager.getType(column.getTypeSignature());
            hiveTypes[i] = column.getHiveType();

            StructField field = rowInspector.getStructFieldRef(column.getName());
            structFields[i] = field;
            fieldInspectors[i] = field.getFieldObjectInspector();
        }
    }

    @Override
    public long getCompletedBytes()
    {
        if (!closed) {
            updateCompletedBytes();
        }
        return completedBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    private void updateCompletedBytes()
    {
        try {
            @SuppressWarnings("NumericCastThatLosesPrecision")
            long newCompletedBytes = (long) (totalBytes * recordReader.getProgress());
            completedBytes = min(totalBytes, max(completedBytes, newCompletedBytes));
        }
        catch (IOException ignored) {
        }
    }

    @Override
    public Type getType(int field)
    {
        return types[field];
    }

    @Override
    public boolean advanceNextPosition()
    {
        try {
            if (closed || !recordReader.next(key, value)) {
                close();
                return false;
            }

            // reset loaded flags
            Arrays.fill(loaded, false);

            // decode value
            rowData = deserializer.deserialize(value);

            return true;
        }
        catch (IOException | SerDeException | RuntimeException e) {
            closeWithSuppression(this, e);
            if (e instanceof TextLineLengthLimitExceededException) {
                throw new PrestoException(HIVE_BAD_DATA, "Line too long in text file: " + path, e);
            }
            throw new PrestoException(HIVE_CURSOR_ERROR, e);
        }
    }

    @Override
    public boolean getBoolean(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        validateType(fieldId, boolean.class);
        if (!loaded[fieldId]) {
            parseBooleanColumn(fieldId);
        }
        return booleans[fieldId];
    }

    private void parseBooleanColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            Object fieldValue = ((PrimitiveObjectInspector) fieldInspectors[column]).getPrimitiveJavaObject(fieldData);
            checkState(fieldValue != null, "fieldValue should not be null");
            booleans[column] = (Boolean) fieldValue;
            nulls[column] = false;
        }
    }

    @Override
    public long getLong(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        validateType(fieldId, long.class);
        if (!loaded[fieldId]) {
            parseLongColumn(fieldId);
        }
        return longs[fieldId];
    }

    private void parseLongColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            Object fieldValue = ((PrimitiveObjectInspector) fieldInspectors[column]).getPrimitiveJavaObject(fieldData);
            checkState(fieldValue != null, "fieldValue should not be null");
            longs[column] = getLongExpressedValue(fieldValue, hiveStorageTimeZone);
            nulls[column] = false;
        }
    }

    private static long getLongExpressedValue(Object value, DateTimeZone hiveTimeZone)
    {
        if (value instanceof Date) {
            long storageTime = ((Date) value).getTime();
            // convert date from VM current time zone to UTC
            long utcMillis = storageTime + DateTimeZone.getDefault().getOffset(storageTime);
            return TimeUnit.MILLISECONDS.toDays(utcMillis);
        }
        if (value instanceof Timestamp) {
            // The Hive SerDe parses timestamps using the default time zone of
            // this JVM, but the data might have been written using a different
            // time zone. We need to convert it to the configured time zone.

            // the timestamp that Hive parsed using the JVM time zone
            long parsedJvmMillis = ((Timestamp) value).getTime();

            // remove the JVM time zone correction from the timestamp
            DateTimeZone jvmTimeZone = DateTimeZone.getDefault();
            long hiveMillis = jvmTimeZone.convertUTCToLocal(parsedJvmMillis);

            // convert to UTC using the real time zone for the underlying data
            long utcMillis = hiveTimeZone.convertLocalToUTC(hiveMillis, false);

            return utcMillis;
        }
        if (value instanceof Float) {
            return floatToRawIntBits(((Float) value));
        }
        return ((Number) value).longValue();
    }

    @Override
    public double getDouble(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        validateType(fieldId, double.class);
        if (!loaded[fieldId]) {
            parseDoubleColumn(fieldId);
        }
        return doubles[fieldId];
    }

    private void parseDoubleColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            Object fieldValue = ((PrimitiveObjectInspector) fieldInspectors[column]).getPrimitiveJavaObject(fieldData);
            checkState(fieldValue != null, "fieldValue should not be null");
            doubles[column] = ((Number) fieldValue).doubleValue();
            nulls[column] = false;
        }
    }

    @Override
    public Slice getSlice(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        validateType(fieldId, Slice.class);
        if (!loaded[fieldId]) {
            parseStringColumn(fieldId);
        }
        return slices[fieldId];
    }

    private void parseStringColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            Object fieldValue = ((PrimitiveObjectInspector) fieldInspectors[column]).getPrimitiveWritableObject(fieldData);
            checkState(fieldValue != null, "fieldValue should not be null");
            BinaryComparable hiveValue;
            if (fieldValue instanceof Text) {
                hiveValue = (Text) fieldValue;
            }
            else if (fieldValue instanceof BytesWritable) {
                hiveValue = (BytesWritable) fieldValue;
            }
            else if (fieldValue instanceof HiveVarcharWritable) {
                hiveValue = ((HiveVarcharWritable) fieldValue).getTextValue();
            }
            else if (fieldValue instanceof HiveCharWritable) {
                hiveValue = ((HiveCharWritable) fieldValue).getTextValue();
            }
            else {
                throw new IllegalStateException("unsupported string field type: " + fieldValue.getClass().getName());
            }

            // create a slice view over the hive value and trim to character limits
            Slice value = Slices.wrappedBuffer(hiveValue.getBytes(), 0, hiveValue.getLength());
            Type type = types[column];
            if (isVarcharType(type)) {
                value = truncateToLength(value, type);
            }
            if (isCharType(type)) {
                value = truncateToLengthAndTrimSpaces(value, type);
            }

            // store a copy of the bytes, since the hive reader can reuse the underlying buffer
            slices[column] = Slices.copyOf(value);
            nulls[column] = false;
        }
    }

    private void parseDecimalColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            Object fieldValue = ((PrimitiveObjectInspector) fieldInspectors[column]).getPrimitiveJavaObject(fieldData);
            checkState(fieldValue != null, "fieldValue should not be null");

            HiveDecimal decimal = (HiveDecimal) fieldValue;
            DecimalType columnType = (DecimalType) types[column];
            BigInteger unscaledDecimal = rescale(decimal.unscaledValue(), decimal.scale(), columnType.getScale());

            if (columnType.isShort()) {
                longs[column] = unscaledDecimal.longValue();
            }
            else {
                slices[column] = Decimals.encodeUnscaledValue(unscaledDecimal);
            }
            nulls[column] = false;
        }
    }

    @Override
    public Object getObject(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        validateType(fieldId, Block.class);
        if (!loaded[fieldId]) {
            parseObjectColumn(fieldId);
        }
        return objects[fieldId];
    }

    private void parseObjectColumn(int column)
    {
        loaded[column] = true;

        Object fieldData = rowInspector.getStructFieldData(rowData, structFields[column]);

        if (fieldData == null) {
            nulls[column] = true;
        }
        else {
            objects[column] = getBlockObject(types[column], fieldData, fieldInspectors[column]);
            nulls[column] = false;
        }
    }

    @Override
    public boolean isNull(int fieldId)
    {
        checkState(!closed, "Cursor is closed");

        if (!loaded[fieldId]) {
            parseColumn(fieldId);
        }
        return nulls[fieldId];
    }

    private void parseColumn(int column)
    {
        Type type = types[column];
        if (BOOLEAN.equals(type)) {
            parseBooleanColumn(column);
        }
        else if (BIGINT.equals(type)) {
            parseLongColumn(column);
        }
        else if (INTEGER.equals(type)) {
            parseLongColumn(column);
        }
        else if (SMALLINT.equals(type)) {
            parseLongColumn(column);
        }
        else if (TINYINT.equals(type)) {
            parseLongColumn(column);
        }
        else if (REAL.equals(type)) {
            parseLongColumn(column);
        }
        else if (DOUBLE.equals(type)) {
            parseDoubleColumn(column);
        }
        else if (isVarcharType(type) || VARBINARY.equals(type)) {
            parseStringColumn(column);
        }
        else if (isCharType(type)) {
            parseStringColumn(column);
        }
        else if (isStructuralType(hiveTypes[column])) {
            parseObjectColumn(column);
        }
        else if (DATE.equals(type)) {
            parseLongColumn(column);
        }
        else if (TIMESTAMP.equals(type)) {
            parseLongColumn(column);
        }
        else if (type instanceof DecimalType) {
            parseDecimalColumn(column);
        }
        else {
            throw new UnsupportedOperationException("Unsupported column type: " + type);
        }
    }

    private void validateType(int fieldId, Class<?> type)
    {
        if (!types[fieldId].getJavaType().equals(type)) {
            // we don't use Preconditions.checkArgument because it requires boxing fieldId, which affects inner loop performance
            throw new IllegalArgumentException(format("Expected field to be %s, actual %s (field %s)", type, types[fieldId], fieldId));
        }
    }

    @Override
    public void close()
    {
        // some hive input formats are broken and bad things can happen if you close them multiple times
        if (closed) {
            return;
        }
        closed = true;

        updateCompletedBytes();

        try {
            recordReader.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
