/*
 GETL - based package in Groovy, which automates the work of loading and transforming data. His name is an acronym for "Groovy ETL".

 GETL is a set of libraries of pre-built classes and objects that can be used to solve problems unpacking,
 transform and load data into programs written in Groovy, or Java, as well as from any software that supports
 the work with Java classes.

 Copyright (C) 2013-2017  Alexsey Konstantonov (ASCRUS)

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Lesser General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License and
 GNU Lesser General Public License along with this program.
 If not, see <http://www.gnu.org/licenses/>.
*/

package getl.vertica

import groovy.transform.InheritConstructors

import getl.csv.CSVDataset
import getl.data.*
import getl.driver.Driver
import getl.exception.ExceptionGETL
import getl.utils.*
import getl.jdbc.*

/**
 * Vertica driver class
 * @author Alexsey Konstantinov
 *
 */
@InheritConstructors
class VerticaDriver extends JDBCDriver {
	VerticaDriver () {
		super()

		defaultSchemaName = 'PUBLIC'

        addPKFieldsToUpdateStatementFromMerge = true

		methodParams.register('createDataset', ['orderBy', 'segmentedBy', 'unsegmented', 'partitionBy'])
        methodParams.register('eachRow', ['label'])
		methodParams.register('bulkLoadFile',
				['loadMethod', 'rejectMax', 'enforceLength', 'compressed', 'exceptionPath', 'rejectedPath',
				 'expression', 'location', 'abortOnError', 'maskDate', 'maskTime', 'maskDateTime',
				 'parser', 'streamName'])
		methodParams.register('unionDataset', ['direct'])
	}

    @Override
    public Map getSqlType () {
        Map res = super.getSqlType()
        res.DOUBLE.name = 'double precision'
        res.BLOB.name = 'varbinary'
        res.TEXT.name = 'long varchar'

        return res
    }

    @Override
    public List<Driver.Support> supported() {
        return super.supported() +
				[Driver.Support.LOCAL_TEMPORARY, Driver.Support.GLOBAL_TEMPORARY, Driver.Support.SEQUENCE,
				 Driver.Support.BLOB, Driver.Support.CLOB, Driver.Support.UUID,
				 Driver.Support.TIME, Driver.Support.DATE, Driver.Support.BOOLEAN]
    }

    @Override
    public List<Driver.Operation> operations() {
        return super.operations() +
                [Driver.Operation.CLEAR, Driver.Operation.DROP, Driver.Operation.EXECUTE, Driver.Operation.CREATE,
                 Driver.Operation.BULKLOAD]
    }

	@Override
	public String defaultConnectURL () {
		return 'jdbc:vertica://{host}/{database}'
	}

	@Override
	protected List<Map> getIgnoreWarning () {
		List<Map> res = []
		res << [errorCode: 4486, sqlState: '0A000']

		return res
	}

	@Override
	protected String createDatasetExtend(Dataset dataset, Map params) {
		def result = ''
		def temporary = ((dataset.sysParams.type as JDBCDataset.Type)in [JDBCDataset.Type.GLOBAL_TEMPORARY, JDBCDataset.Type.LOCAL_TEMPORARY])
		if (temporary && params.onCommit != null && params.onCommit) result += 'ON COMMIT PRESERVE ROWS '
		if (params.orderBy != null && !params.orderBy.isEmpty()) result += "ORDER BY ${params.orderBy.join(", ")} "
		if (params.segmentedBy != null) result += "SEGMENTED BY ${params.segmentedBy} "
		if (params.unsegmented != null && params.unsegmented) result += "UNSEGMENTED ALL NODES "
		if (params.partitionBy != null) result += "PARTITION BY ${params.partitionBy} "

		return result
	}

	@Override
	public void bulkLoadFile(CSVDataset source, Dataset dest, Map bulkParams, Closure prepareCode) {
		def params = bulkLoadFilePrepare(source, dest as JDBCDataset, bulkParams, prepareCode)

		if (source.fieldDelimiter == null || source.fieldDelimiter.length() != 1) throw new ExceptionGETL('Required one char field delimiter')
		if (source.rowDelimiter == null || source.rowDelimiter.length() != 1) throw new ExceptionGETL('Required one char row delimiter')
		if (source.quoteStr == null || source.quoteStr.length() != 1) throw new ExceptionGETL('Required one char quote str')

		def fieldDelimiter = "E'\\x${Integer.toHexString(source.fieldDelimiter.bytes[0])}'"
		def rowDelimiter = "E'\\x${Integer.toHexString(source.rowDelimiter.bytes[0])}'"
		def quoteStr = "E'\\x${Integer.toHexString(source.quoteStr.bytes[0])}'"
		def header = source.header
		def nullAsValue = (source.nullAsValue != null)?"\nNULL AS '${source.nullAsValue}'":''
		def isGzFile = source.isGzFile

		String parserText = ''
		if (params.parser != null) {
			String parserFunc = params.parser.function
			if (parserFunc == null) throw new ExceptionGETL('Required parser function name')
			Map<String, Object> parserOptions = params.parser.options
			if (parserOptions != null) {
				def ol = []
				parserOptions.each { String name, def value ->
					if (value instanceof String) {
						ol << "$name='$value'"
					}
					else {
						ol << "$name=$value"
					}
				}
				parserText = "\nWITH PARSER $parserFunc(${ol.join(', ')})"
			}
			else {
				parserText = "\nWITH PARSER $parserFunc()"
			}
		}

		List<Map> map = params.map
		Map<String, String> expressions = params.expression?:[:]
		String loadMethod = ListUtils.NotNullValue([params.loadMethod, 'AUTO'])
		boolean enforceLength = BoolUtils.IsValue(params.enforceLength, true)
		boolean autoCommit = ListUtils.NotNullValue([BoolUtils.IsValue(params.autoCommit, null), dest.connection.tranCount == 0])
		String compressed = ListUtils.NotNullValue([params.compressed, (isGzFile?'GZIP':null)])
		String exceptionPath = params.exceptionPath
		String rejectedPath = params.rejectedPath
		Integer rejectMax = params.rejectMax
		boolean abortOnError = ListUtils.NotNullValue([BoolUtils.IsValue(params.abortOnError, null),
													   (!(rejectedPath != null || exceptionPath != null))])
		String location = params.location
		String onNode = (location != null)?(' ON ' + location):''

		String streamName = params.streamName

		List fileList
		if (params.files != null) {
			fileList = []
			params.files.each { String fileName ->
				fileList << "'${fileName.replace("\\", "/")}'$onNode"
			}
		}
		else if (params.fileMask != null) {
			fileList =  ["'${params.fileMask}'$onNode"]
		}
		else {
			fileList =  ["'${source.fullFileName().replace("\\", "/")}'$onNode"]
		}

		if (compressed != null) {
			def f = []
			fileList.each { file ->
				f << "$file $compressed"
			}
			fileList = f
		}
		def fileName = fileList.join(',')

		if (exceptionPath != null) FileUtils.ValidFilePath(exceptionPath)
		if (rejectedPath != null) FileUtils.ValidFilePath(rejectedPath)

		StringBuilder sb = new StringBuilder()
		sb << "COPY ${fullNameDataset(dest)} (\n"

		JDBCConnection con = dest.connection as JDBCConnection
		String formatDate = ListUtils.NotNullValue([params.maskDate, con.maskDate])
		String formatTime = ListUtils.NotNullValue([params.maskTime, con.maskTime])
		String formatDateTime = ListUtils.NotNullValue([params.maskDateTime, con.maskDateTime])

		List columns = []
		List options = []
		map.each { Map f ->
			if (f.field != null) {
				def fieldName = (dest as JDBCDataset).sqlObjectName(f.field.name)
				columns << fieldName
				switch (f.field.type) {
					case Field.Type.BLOB:
						options << "$fieldName format 'hex'"
						break
					case Field.Type.DATE:
						if (f.format != null && f.format != '')
							options << "$fieldName format '${f.format}'"
						else
							if (formatDate != null) options << "$fieldName format '$formatDate'"
						break
					case Field.Type.TIME:
						if (f.format != null && f.format != '')
							options << "$fieldName format '${f.format}'"
						else
							if (formatTime != null) options << "$fieldName format '$formatTime'"
						break
					case Field.Type.DATETIME:
						if (f.format != null && f.format != '')
							options << "$fieldName format '${f.format}'"
						else
							if (formatDateTime != null) options << "$fieldName format '$formatDateTime'"
				}
			}
			else if (f.alias != null) {
				columns << f.alias
			}
			else {
				columns << "${fieldPrefix}__notfound__${f.column}${fieldPrefix} FILLER varchar(8000)"
			}
		}

		expressions.each { String col, String expr ->
			if (dest.fieldByName(col) == null) throw new ExceptionGETL("Expression field \"$col\" not found")
			if (expr != null) {
				col = (dest as JDBCDataset).sqlObjectName(col)
				columns << "$col AS $expr"
			}
		}

		sb << columns.join(',\n')
		sb << '\n)\n'

		if (!options.isEmpty()) {
			sb << 'COLUMN OPTION (\n'
			sb << options.join(',\n')
			sb << '\n)\n'
		}

		sb << """FROM ${(location == null)?"LOCAL ":""}$fileName $parserText
DELIMITER AS $fieldDelimiter$nullAsValue
ENCLOSED BY $quoteStr
RECORD TERMINATOR $rowDelimiter
"""
		if (header) sb << 'SKIP 1\n'
		if (rejectMax != null) sb << "REJECTMAX ${rejectMax}\n"
		if (exceptionPath != null) sb << "EXCEPTIONS '${exceptionPath}'$onNode\n"
		if (rejectedPath != null) sb << "REJECTED DATA '${rejectedPath}'$onNode\n"
		if (enforceLength) sb << 'ENFORCELENGTH\n'
		if (abortOnError) sb << 'ABORT ON ERROR\n'
		sb << "${loadMethod}\n"
		if (streamName != null) sb << "STREAM NAME '$streamName'\n"
		if (!autoCommit) sb << 'NO COMMIT\n'

		def sql = sb.toString()
		dest.sysParams.sql = sql
		//println sql

		dest.writeRows = 0
		dest.updateRows = 0
		try {
			long count = executeCommand(sql, [isUpdate: true])
			dest.writeRows = count
			dest.updateRows = count
		}
		catch (Exception e) {
//			Logs.Dump(e, getClass().name + '.bulkLoad', "${source.objectName}->${dest.objectName}", sql)
			throw e
		}
	}

	@Override
	protected String sessionID() {
		String res = null
		def rows = sqlConnect.rows('SELECT session_id FROM CURRENT_SESSION')
		if (!rows.isEmpty()) res = rows[0].session_id

		return res
	}

	@Override
	protected Map unionDatasetMergeParams (JDBCDataset source, JDBCDataset target, Map procParams) {
		def res = super.unionDatasetMergeParams(source, target, procParams)
		res.direct = (procParams.direct != null && procParams.direct)?'/*+direct*/':''

		return res
	}

	@Override
	protected String unionDatasetMergeSyntax () {
        return '''MERGE {direct} INTO {target} t
  USING {source} s ON {join}
  WHEN MATCHED THEN UPDATE SET 
    {set}
  WHEN NOT MATCHED THEN INSERT ({fields})
    VALUES ({values})'''
	}

	@Override
	protected String getChangeSessionPropertyQuery() { return 'SET {name} TO {value}' }

	@Override
	public void sqlTableDirective (Dataset dataset, Map params, Map dir) {
        if (params.label != null) {
            dir."afterselect" = "/*+label(${params.label})*/"
        }

        def res = (List<String>)[]
		if (params.limit != null) {
            res << "LIMIT ${params.limit}"
        }
        if (params.offset != null) {
            res << "OFFSET ${params.offset}"
        }
        if (!res.isEmpty()) {
            dir.afterOrderBy = res.join('\n')
        }
	}

	@Override
	public void prepareField (Field field) {
		super.prepareField(field)

		if (field.typeName != null) {
			if (field.typeName.matches("(?i)UUID")) {
				field.type = Field.Type.UUID
				field.dbType = java.sql.Types.VARCHAR
				field.length = 36
				field.precision = null
				return
			}
		}
	}

	@Override
	public boolean blobReadAsObject () { return false }

	@Override
	public String blobMethodWrite (String methodName) {
		return """void $methodName (java.sql.Connection con, java.sql.PreparedStatement stat, int paramNum, byte[] value) {
	if (value == null) { 
		stat.setNull(paramNum, java.sql.Types.BLOB) 
	}
	else {
		def stream = new ByteArrayInputStream(value)
		stat.setBinaryStream(paramNum, stream, value.length)
		stream.close()
	}
}"""
	}
}