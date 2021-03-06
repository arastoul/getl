/*
 GETL - based package in Groovy, which automates the work of loading and transforming data. His name is an acronym for "Groovy ETL".

 GETL is a set of libraries of pre-built classes and objects that can be used to solve problems unpacking,
 transform and load data into programs written in Groovy, or Java, as well as from any software that supports
 the work with Java classes.
 
 Copyright (C) 2013-2015  Alexsey Konstantonov (ASCRUS)

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

package getl.utils

import java.util.regex.Matcher
import java.util.regex.Pattern
import groovy.transform.InheritConstructors

import getl.data.Field
import getl.exception.ExceptionGETL


/**
 * Analize and processing macro path class
 * @author Alexsey Konstantinov
 *
 */
@InheritConstructors
class Path {
	protected ParamMethodValidator methodParams = new ParamMethodValidator()
	
	Path () {
		registerMethod()
	}
	
	Path (Map params) {
		registerMethod()
		compile(params)
	}
	
	private void registerMethod () {
		methodParams.register("compile", ["mask", "sysVar", "patterns", "vars"])
	} 
	
	/**
	 * Change from process path Windows file separator to Unix separator  
	 */
	public boolean changeSeparator = (File.separatorChar != '/')
	
	/**
	 * Ignoring converting error and set null value
	 */
	public boolean ignoreConvertError = false
	
	/**
	 * Original string mask
	 * @return
	 */
	private String maskStr
	public String getMaskStr () { maskStr }
	
	/**
	 * Path elements
	 * @return
	 */
	private List<Map> elements = []
	public List<Map> getElements () { elements }
	
	/**
	 * SQL like elements
	 * @return
	 */
	private List<Map> likeElements = []
	public List<Map> getLikeElements () { likeElements }
	
	/**
	 * Count level for mask 
	 * @return
	 */
	public int getCountLevel () { elements.size() }
	
	/**
	 * Root path with mask
	 * @return
	 */
	private String rootPath
	public String getRootPath () { this.rootPath }
	
	/**
	 * Count elements in path
	 * @return
	 */
	private int numLocalPath
	public int getNumLocalPath () { this.numLocalPath }
	
	/**
	 * Expression file path with mask
	 * @return
	 */
	private String maskPath
	public String getMaskPath () { this.maskPath }

	/**
	 * Expression folder path with mask		
	 * @return
	 */
	private String maskFolder
	public String getMaskFolder () { this.maskFolder }
	
	/**
	 * Expression mask file
	 * @return
	 */
	private String maskFile
	public String getMaskFile () { this.maskFile }
	
	/**
	 * Expression folder path with mask for SQL like
	 * @return
	 */
	private String likeFolder
	public String getLikeFolder () { this.likeFolder }
	
	/**
	 * Expression mask file for SQL like
	 * @return
	 */
	private String likeFile
	public String getLikeFile () { this.likeFile }
	
	/**
	 * Used variables in mask<br><br>
	 * <b>Field for var:</b>
	 * <ul>
	 * <li>Field.Type type	- type var
	 * <li>String format	- parse format
	 * <li>int len			- length value of variable
	 * <li>int lenMin		- minimum length of variable
	 * <li>int lenMax		- maximum length of variable
	 * </ul>
	 * @return
	 */
	private final Map<String, Map> vars = [:]
	public Map<String, Map> getVars () { this.vars }
	
	private Pattern maskPathPattern
		
	/**
	 * Compile path mask<br><br>
	 * <b>Parameters</b>
	 * <ul>
	 * <li>String mask - mask of filename<br>
	 * <li>boolean sysVar - include system variables #file_date and #file_size
	 * <li>Map patterns - pattern of variable for use in generation regular expressions
	 * <li>Map<String, Map> vars - attributes of variable
	 * </ul>
	 * @param params - parameters
	 */
	public void compile (Map params) {
		if (params == null) params = [:]
		methodParams.validation("compile", params)
		maskStr = params.mask
		if (maskStr == null) throw new ExceptionGETL("Required parameter \"mask\"")
		boolean sysVar = (params.sysVar != null)?params.sysVar:false
		Map patterns = params.patterns?:[:] 
		Map<String, Map<String, Object>> varAttr = params.vars?:[:]
		
		elements.clear()
		likeElements.clear()
		vars.clear()
		rootPath = null
		maskPath = null
		maskPathPattern = null
		maskFolder = null
		maskFile = null
		likeFolder = null
		likeFile = null
		numLocalPath = -1
	
//		def rmask = maskStr.replace(".", "[.]").replace("*", ".*").replace("+", "\\+").replace("-", "\\-")
		def rmask = FileUtils.FileMaskToMathExpression(maskStr)

		String[] d = rmask.split("/")
		StringBuilder rb = new StringBuilder()
		
		for (int i = 0; i < d.length; i++) {
			Map p = [:]
			p.vars = []
			StringBuilder b = new StringBuilder()
			
			int f = 0
			int s = d[i].indexOf('{')
			while (s >= 0) {
				int e = d[i].indexOf('}', s)
				if (e >= 0) {
					b.append(d[i].substring(f, s))
					
					def vn = d[i].substring(s + 1, e).toLowerCase()
					def pm = patterns.get(vn) 
					if (pm == null) {
						def vt = varAttr.get(vn)
						def type = vt?.type as Field.Type
						if (type != null && type instanceof String) type = Field.Type."$type"
						if (type in [Field.Type.DATE, Field.Type.DATETIME, Field.Type.TIME]) {
							String df
							if (vt.format != null) {
								df = vt.format
							}
							else {
								if (type == Field.Type.DATE) {
									df = "yyyy-MM-dd"
								}
								else if (type == Field.Type.TIME) {
									df = "HH:mm:ss"
								}
								else {
									df = "yyyy-MM-dd HH:mm:ss"
								}
							}
							
							def vm = df.toLowerCase()
							vm = vm.replace("d", "\\d").replace("y", "\\d").replace("m", "\\d").replace("h", "\\d").replace("s", "\\d")
							b.append("($vm)")
						}
						else if (type in [Field.Type.BIGINT, Field.Type.INTEGER]) {
							if (vt?.len != null) {
								b.append("(\\d{${vt.len}})")
							}
							else if (vt?.lenMin != null && vt?.lenMax != null) {
								b.append("(\\d{${vt.lenMin},${vt.lenMax}})")
							}
							else {
								b.append("(\\d+)")
							}
						}
						else {
							if (vt?.format != null) {
								b.append("(${vt.format})")	
							}
							else {
								b.append("(.+)")
							}
						}
					}
					else {
						b.append("(${pm})")
					}
					
					if (p.vars.find { it == vn} != null) throw new ExceptionGETL("Duplicate variable \"${vn}\"")
					p.vars.add(vn)
					
					f = e + 1
				}
				s = d[i].indexOf('{', f)
			}
			if (f > 0 && f < d[i].length() - 1) {
				b.append(d[i].substring(f))
			}
			
			p.mask = (p.vars.size() == 0)?d[i]:b.toString()
			p.like = (p.vars.size() == 0)?d[i]:'%'
			elements.add(p)
			
			if (p.vars.size() == 0) {
				if (numLocalPath == -1 && i < d.length - 1)
					rb.append("/" + d[i])
			}
			else
				if (numLocalPath == -1) numLocalPath = i
		}
		rootPath = (rb.length() == 0)?".":rb.toString().substring(1)

		for (int i = 0; i < elements.size(); i++) {
			List<String> v = elements[i].vars
			for (int x = 0; x < v.size(); x++) {
				vars.put(v[x] as String, [:])
			}
		}
		patterns.each { k, v ->
			def p = vars.get(k) as Map
			if (p != null) {
				p.pattern = v
			}
		}
		generateMaskPattern()
		
		if (sysVar) {
			vars.put("#file_date", [:])
			vars.put("#file_size", [:])
		}
		
		varAttr.each { name, values ->
			def fn = name.toLowerCase()
			Map<String, Object> attrList = vars.get(fn)
			if (attrList != null) {
				values.each { String attr, value ->
					def a = attr.toLowerCase()
					if (!(a in ["type", "format", "len", "lenmin", "lenmax"])) throw new ExceptionGETL("Unknown variable attribute \"${attr}\"") 
					attrList.put(a, value)
				}
			}
		}
		
		maskFile = maskStr
		likeFile = maskStr
		vars.each { key, value ->
			def vo = "(?i)(\\{${key}\\})"
			maskFile = maskFile.replaceAll(vo, "\\\$\$1")
			
			likeFile = likeFile.replace("{$key}", "%")
		}
		likeFile = likeFile.replace('*', '%').replace('.', '\\.')
	}
	
	/**
	 * Generation mask path pattern on elements
	 */
	public void generateMaskPattern () {
		StringBuilder b = new StringBuilder()
		b.append("(?i)")
		for (int i = 0; i < elements.size(); i++) {
			b.append(elements[i].mask)
			if (i < elements.size() - 1) b.append("/")
		}
		maskPath = b.toString()
		maskPathPattern = Pattern.compile(maskPath)
		maskFolder = null
		likeFolder = null
		
		if (!elements.isEmpty() /*&& elements[elements.size() - 2].vars.size() > 0*/) {
			StringBuilder mp = new StringBuilder()
			StringBuilder lp = new StringBuilder()
			mp.append("(?i)")
			for (int i = 0; i < elements.size() - 1; i++) {
				mp.append(elements[i].mask + "/")
				lp.append(elements[i].like.replace('.', '\\.') + "/")
			}
			maskFolder = mp.toString() + "(.+)"
			likeFolder = lp.toString()
			if (elements.size() > 1) likeFolder = likeFolder.substring(0, lp.length() - 1)
		}
	}
	
	/**
	 * Generation mask path pattern on elements 
	 * @param countElements
	 * @return
	 */
	public Pattern generateMaskPattern (int countElements) {
		if (countElements > elements.size()) throw new ExceptionGETL("Can not generate pattern on ${countElements} level for ${elements.size()} elements")
		StringBuilder b = new StringBuilder()
		b.append("(?i)")
		for (int i = 0; i < countElements; i++) {
			b.append(elements[i].mask)
			if (i < countElements - 1) b.append("/")
		}
		def mp = b.toString()
		Pattern.compile(mp)
	}
	
	/**
	 * Get all files in specific path
	 * @param path
	 * @return
	 */
	public List<Map> getFiles(String path) {
		File dir = new File(path)
		File[] f = dir.listFiles()
		
		List<Map> l = []
		
		if (f == null) return l
		
		for (int i = 0; i < f.length; i++) {
			if (f[i].isDirectory()) {
				List<Map> n = getFiles(path + "/" + f[i].getName())
				for (int x = 0; x < n.size(); x++) {
					def m = [:]
					m.fileName = n[x].fileName
					l << m
				}
			}
			else
				if (f[i].isFile()) {
					def m = [:]
					m.fileName = path +"/" + f[i].getName()
					l << m
				}
		}
		l
	}
	
	/**
	 * Get all files with root path
	 * @return
	 */
	public List<Map> getFiles() {
		getFiles(rootPath)
	}
	
	/**
	 * Analize file with mask and return value of variables 
	 * @param file
	 * @return
	 */
	public Map analizeFile(String fileName) {
		analize(fileName, false)
	}
	
	/**
	 * Analize dir with mask and return value of variables
	 * @param dirName
	 * @return
	 */
	public Map analizeDir(String dirName) {
		analize(dirName, true)
	}
	
	/**
	 * Analize file or directory
	 * @param fileName
	 * @return
	 */
	private Map analize(String fileName, boolean isDir) {
		def fn = fileName
		if (fn == null) return null
		
		if (changeSeparator) fn = fn.replace('\\', '/')
		
		Integer countDirs
		String pattern
		
		if (isDir) {
			countDirs = fileName.split("/").length
			pattern = generateMaskPattern(countDirs)
		}
		else {
			pattern = maskPathPattern
		}
		
		if (!fn.matches(pattern)) return null
		
		def res = [:]
		
		
		Matcher mat
		mat = fn =~ pattern
		if (mat.groupCount() >= 1 && ((List)mat[0]).size() > 1) {
			int i = 0
            def isError = false
			vars.each { key, value ->
                if (isError) return

                //noinspection GroovyAssignabilityCheck
                def v = (mat[0][i + 1]) as String

				if (v?.length() == 0) v = null
				
				if (v != null && value.len != null && v.length() != value.len) {
					if (!ignoreConvertError) throw new ExceptionGETL("Invalid [${key}]: length value \"${v}\" <> ${value.len}")
					v = null
				}

                //noinspection GroovyAssignabilityCheck
                if (v != null && value.lenMin != null && v.length() < Integer.valueOf(value.lenMin)) {
					if (!ignoreConvertError) throw new ExceptionGETL("Invalid [${key}]: length value \"${v}\" < ${value.lenMin}")
					v = null
				}
                //noinspection GroovyAssignabilityCheck
                if (v != null && value.lenMax != null && v.length() > Integer.valueOf(value.lenMax)) {
					if (!ignoreConvertError) throw new ExceptionGETL("Invalid [${key}]: length value \"${v}\" > ${value.lenMax}")
					v = null
				}
				
				if (value.type != null && v != null) {
					def type = value.type
					if (type instanceof String) type = Field.Type."$type"
					switch (type) {
						case Field.Type.DATE:
							def format = (value.format != null)?value.format:"yyyy-MM-dd"
							try {
								v = DateUtils.ParseDate(format, v, ignoreConvertError)
							}
							catch (Exception e) {
								throw new ExceptionGETL("Invalid [${key}]: can not parse value \"${v}\" to date: ${e.message}")
							}
                            isError = (v == null)
							break
						case Field.Type.DATETIME:
							def format = (value.format != null)?value.format:"yyyyMMddHHmmss"
							try {
								v = DateUtils.ParseDate(format, v, ignoreConvertError)
							}
							catch (Exception e) {
								throw new ExceptionGETL("Invalid [${key}]: can not parse value \"${v}\" to datetime: ${e.message}")
							}
                            isError = (v == null)
							break
						case Field.Type.INTEGER:
							try {
								v = Integer.valueOf(v)
							}
							catch (Exception e) {
								if (!ignoreConvertError) throw new ExceptionGETL("Invalid [${key}]: can not parse value \"${v}\" to integer: ${e.message}")
								v = null
							}
                            isError = (v == null)
							break
						case Field.Type.BIGINT:
							try {
								v = Long.valueOf(v)
							}
							catch (Exception e) {
								if (!ignoreConvertError) throw new ExceptionGETL("Invalid [${key}]: can not parse value \"${v}\" to bigint: ${e.message}")
								v = null
							}
                            isError = (v == null)
							break
						case Field.Type.STRING:
							break
						default:
							throw new ExceptionGETL("Unknown type ${value.type}")
					}
				}
                if (isError) return
				
				res.put(key, v)
				i++
			}
            if (isError) return null
		}
		
		return res
	}
	
	/**
	 * Filter files with mask
	 * @param files
	 * @return
	 */
	public List<Map> filterFiles(List<Map> files) {
//		boolean changeSeparator = (File.separatorChar != '/')
//		Pattern mask = Pattern.compile(maskPath)
		List<Map> res = []
		files.each { file ->
			def m = analizeFile(file.filename as String)
			if (m != null) res << file
		}

		return res
	}
	
	/**
	 * Filter files from root path with mask
	 * @return 
	 */
	public List<Map> filterFiles() {
		filterFiles(getFiles())
	}
	
	/**
	 * Generation file name with variables
	 * @param varValues
	 * @return
	 */
	public String generateFileName(Map varValues) {
		generateFileName(varValues, true)
	}
	
	/**
	 * Generation file name with variables
	 * @param varValues - Value of variables
	 * @param formatValue - format value of variable
	 * @return
	 */
	@groovy.transform.CompileStatic
	public String generateFileName(Map<String, Object> varValues, boolean formatValue) {
		def v = [:]
		if (formatValue) {
			vars.each { key, value ->
				def val = formatVariable(key, varValues.get(key))
				v.put(key, val)
			}
		}
		else {
			vars.each { key, value ->
				v.put(key, varValues.get(key))
			}
		}
		def res = GenerationUtils.EvalText(maskFile, v)
		
		res
	}

	/**
	 * 		
	 * @param varName
	 * @param value
	 * @return
	 */
	public String formatVariable (String varName, def value) {
		if (!vars.containsKey(varName)) throw new ExceptionGETL("Variable ${varName} not found")
		def v = vars.get(varName)
		def type = v."type"
		if (type == null) {
            type = Field.Type.STRING
        }
        else if (type instanceof String) {
            type = Field.Type."$type"
        }
		
		switch (type) {
			case Field.Type.DATE:
				def format = v.format?:"yyyy-MM-dd"
				value = DateUtils.FormatDate(format, value as Date)
				break
				
			case Field.Type.TIME:
				def format = v.format?:"HH:mm:ss"
				value = DateUtils.FormatDate(format, value as Date)
				break
				
			case Field.Type.DATETIME:
				def format = v.format?:"yyyy-MM-dd HH:mm:ss"
				value = DateUtils.FormatDate(format, value as Date)
				break
		}
		
		value
	}
		
	public String toString () {
		StringBuilder b = new StringBuilder()
		b.append("""original mask: $maskStr
root path: $rootPath
count level in path: $countLevel
count root levels in path: $numLocalPath
mask path: $maskPath
mask folder: $maskFolder
mask file: $maskFile
like folder: $likeFolder
like file: $likeFile
variables: $vars
elements:
""")
		for (int i = 0; i < elements.size(); i++) {
			def pe = elements[i]
			b.append("[${i+1}]:\t")
			b.append(pe.mask)
            List vr = pe.vars
			if (vr.size() > 0) b.append(" [")
			for (int v = 0; v < vr.size(); v++) {
				b.append(vr.get(v))
				if (v < vr.size() - 1)
					b.append(", ")
			}
			if (vr.size() > 0) b.append("]")
			b.append("\n")
		}
		b.toString()
	}
	}