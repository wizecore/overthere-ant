package com.wizecore.overthere.ant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import com.xebialabs.overthere.CmdLine;
import com.xebialabs.overthere.ConnectionOptions;
import com.xebialabs.overthere.OperatingSystemFamily;
import com.xebialabs.overthere.Overthere;
import com.xebialabs.overthere.OverthereConnection;
import com.xebialabs.overthere.OverthereFile;
import com.xebialabs.overthere.RuntimeIOException;
import com.xebialabs.overthere.cifs.CifsConnectionBuilder;
import com.xebialabs.overthere.util.ConsoleOverthereExecutionOutputHandler;
import com.xebialabs.overthere.ssh.SshConnectionBuilder;
import com.xebialabs.overthere.ssh.SshConnectionType;

/**
 * Execution task for ant which working using SSH on Unix target machine, WinRM on Windows target machine.
 * See <a href="https://github.com/xebialabs/overthere">overthere</a> for more info.
 * <p>
 * You can copy a file and execute it, or simply copy file.
 * 
 * <p>Example 1 - copy and execute script.</p>
 * <pre>
 * &lt;overexec host="testwinvm" username="Administrator" password="123" os="WINDOWS" file="install.bat"&gt;
 *   &lt;cmd&gt;install.bat&lt;/cmd&gt;
 * &lt;/overexec&gt;
 * </pre>
 *  
 * <p>Example 2 - create script.</p>
 * <pre>
 * &lt;overexec host="testlinuxvm" username="root" password="r$$t" os="UNIX" toFile="install.sh"&gt;
 *   &lt;content&gt;
 *   echo Installing
 *   echo apt-get install mc
 *   echo apt-get install java-jdk-6
 *   &lt;/content&gt;
 *   &lt;cmd&gt;sh&lt;/cmd&gt;
 *   &lt;arg&gt;install.sh&lt;/arg&gt;
 * &lt;/overexec&gt;
 * </pre>
 * 
 * @ant.task name="overexec" category="overexec"
 * @author huksley
 */
public class OverthereExecute extends Task {
	String host;
	String username;
	String password;
	String os = "WINDOWS"; // UNIX, WINDOWS
	String domain;
	String dir;
	List<OverthereArgument> exec = new ArrayList<OverthereArgument>();
	File file;
	String toFile;
	boolean overwrite;
	String encoding = "UTF-8";
	StringBuffer content;
	boolean temporary = true;
	boolean temporarySet = false;
	boolean https = false;
	String locale = null;
	int retry = 10;
	long retrySleep = 5000;
	String retryMatch = ".*Response code was 401.*";
	int timeout;
	boolean failOnError = true;
	String resultProperty;
	String jumpstation;
	String downloadFile;
	String downloadTo;
	
	@Override
	public void execute() throws BuildException {
		ClassLoader orig = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
			try {
				if (host == null || host.equals("")) {
					throw new NullPointerException("host");
				}
				
				if (domain != null) {
					System.setProperty("java.security.krb5.realm", domain.toUpperCase());
					System.setProperty("java.security.krb5.kdc", domain);
				}
				
				ConnectionOptions options = new ConnectionOptions();
				options.set(ConnectionOptions.ADDRESS, host);
				options.set(ConnectionOptions.USERNAME, domain != null ? (username + "@" + domain.toUpperCase()) : username);
				options.set(ConnectionOptions.PASSWORD, password);
				options.set(ConnectionOptions.OPERATING_SYSTEM, OperatingSystemFamily.valueOf(os.toUpperCase()));
				if (jumpstation != null) { 
					ConnectionOptions jo = new ConnectionOptions();
					jo.set(SshConnectionBuilder.CONNECTION_TYPE, SshConnectionType.TUNNEL);
					for (StringTokenizer tk = new StringTokenizer(jumpstation, ",;\n"); tk.hasMoreTokens();) {
						String n = tk.nextToken();
						String v = "true";
						if (n.indexOf('=') > 0) {
							v = n.substring(n.indexOf('=') + 1).trim();
							n = n.substring(0, n.indexOf('=')).trim();
						}
						if (n.equals("connectionType")) {
							if (v.equals("ssh")) {
								// Default
							} else {
								throw new BuildException("Only connectionType=ssh is supported");
							}
						} else {
							jo.set(n, v);
						}
					}
					options.set(ConnectionOptions.JUMPSTATION, jo);
				}
				
				String proto = "ssh";
				if (os.equals("WINDOWS")) {
					options.set(CifsConnectionBuilder.CONNECTION_TYPE, com.xebialabs.overthere.cifs.CifsConnectionType.WINRM);
					proto = "cifs";
					
					if (https) {
						options.set(CifsConnectionBuilder.WINRM_ENABLE_HTTPS, "true");
						// self-signed certificates are allowed
						options.set(CifsConnectionBuilder.WINRM_HTTPS_CERTIFICATE_TRUST_STRATEGY, "SELF_SIGNED");
					}
					
					if (locale != null) {
						options.set(CifsConnectionBuilder.WINRM_LOCALE, locale);
					}
					
					if (timeout > 0) {
						options.set(CifsConnectionBuilder.WINRM_TIMEMOUT, "PT" + timeout + ".000S");
					}
				}		
				
				OverthereConnection over = Overthere.getConnection(proto, options);
				try {
					OverthereFile wdir = null;
					OverthereFile remoteFile = null;
					
					if (dir != null) {
						wdir = over.getFile(this.dir);
						if (wdir.exists()) {
							log("Working directory " + this.dir);
							over.setWorkingDirectory(wdir);
						} else {
							throw new IOException("No host dir: " + wdir);
						}
					} else {
						wdir = over.getTempFile("1.tmp").getParentFile();
						log("Working directory (automatically determined) " + wdir);
						this.dir = wdir.getPath();
						over.setWorkingDirectory(wdir);
					}
					
					if (file != null || (content != null &&  toFile != null)) {
						if (content != null && file != null) {
							throw new IOException("Must specify either file or inner content");
						}

						if (toFile == null && content != null) {
							throw new IOException("toFile must be specified if content specified!");
						}
												
						if (file != null) {
							if (!file.exists()) {
								throw new IOException("No local file: " + file);
							}
							
							if (toFile == null) {
								toFile = file.getName(); 
							} else {
								temporary = false;
							}
							
							if (os.equals("WINDOWS") && toFile.startsWith("/")) {
								toFile = "C:" + toFile;
								remoteFile = over.getFile(toFile);
							} else
							if (os.equals("WINDOWS") && (toFile.startsWith("C:/") || toFile.startsWith("c:/"))) {
								remoteFile = over.getFile(toFile);
							} else
							if (!os.equals("WINDOWS") && toFile.startsWith("/")) {
								remoteFile = over.getFile(toFile);
							} else {
								remoteFile = over.getFile(wdir, toFile);
							}
							
							if (!remoteFile.exists() || (overwrite || file.lastModified() > remoteFile.lastModified())) {
								byte[] buf = new byte[65535];
								int c = 0;
								log("Copying " + file + " to host " + remoteFile);
								FileInputStream is = new FileInputStream(file);
								try {
									OutputStream os = remoteFile.getOutputStream();
									try {
										while ((c = is.read(buf)) >= 0) {
											if (c > 0) {
												os.write(buf, 0, c);
											}
										}
									} finally {
										os.close();
									}
								} finally {
									is.close();
								}
							}
						} else
						if (content != null) {
							remoteFile = wdir != null ? over.getFile(wdir, toFile) : over.getFile(toFile);
							String scontent = content.toString();
							scontent = scontent.trim();
							scontent = scontent.replaceAll("\\r\\n", "\n");
							scontent = scontent.replaceAll("\\n", "\r\n");
							log("Writing content to host " + remoteFile);
							byte[] bb = scontent.getBytes(encoding);
							OutputStream os = remoteFile.getOutputStream();
							try {
								os.write(bb, 0, bb.length);
							} finally {
								os.close();
							}
						}
					}
					
					try {
						int retryCount = 0;
						boolean retriable;
						do {
							retriable = false;
							try {
								if (exec.size() > 0) {
									CmdLine c = new CmdLine();
									for (int i = 0; i < exec.size(); i++) {
										c.addArgument(getProject().replaceProperties(exec.get(i).getValue()));
									}
									
									int res = over.execute(ConsoleOverthereExecutionOutputHandler.sysoutHandler(), 
											ConsoleOverthereExecutionOutputHandler.syserrHandler(), 
											c);
									if (resultProperty != null) {
										getProject().setProperty(resultProperty, String.valueOf(res));
									}
									if (failOnError) {
										throw new BuildException("Execution failed: " + res);
									}
								}
							} catch (RuntimeIOException rio) {
								if (rio.getCause() instanceof ConnectException && retry > 0) {
									retriable = true;
								}
								if (rio.getCause() instanceof RuntimeIOException && rio.getCause().getMessage().matches(retryMatch) && retry > 0) {
									retriable = true;
								}
								
								if (retriable) {
									if (retryCount < retry) {
										retryCount ++;							
										log("Detected retriable error (" + rio.getCause() + "), retrying " + retryCount + "/" + retry);
										Thread.sleep(retrySleep);
										
									} else {
										throw rio;
									}
								} else {
									throw rio;
								}
							}
						} while (retriable);
					} finally {
						if (temporary && remoteFile != null) {
							log("Deleting " + remoteFile);
							remoteFile.delete();
						}
					}
					
					if (downloadFile != null) {
						File f = new File(downloadFile);
						String localFile = f.getName();
						if (downloadTo != null) {
							File ff = new File(downloadTo);
							if (ff.isDirectory()) {
								localFile = new File(ff, localFile).getPath();
							} else {
								localFile = downloadTo;
							}
						}
						log("Downloading " + downloadFile + " to " + localFile);
						InputStream is = over.getFile(downloadFile).getInputStream();
						try {
							FileOutputStream fos = new FileOutputStream(localFile);
							try {
								byte[] bb = new byte[2048];
								int c = 0;
								while ((c = is.read(bb)) >= 0) {
									if (c > 0) {
										fos.write(bb, 0, c);
									}
								}
							} finally {
								fos.close();
							}
						} finally {
							is.close();
						}
					}
				} finally {
					over.close();
				}
			} catch (Exception e) {
				throw new BuildException(e);
			}
		} finally {
			Thread.currentThread().setContextClassLoader(orig);
		}
	}
	
	public OverthereContent createContent() {
		return new OverthereContent(this);
	}
	
	public OverthereArgument createArg() {
		OverthereArgument arg = new OverthereArgument();
		exec.add(arg);
		return arg;
	}
	
	public OverthereArgument createCmd() {
		OverthereArgument arg = new OverthereArgument();
		exec.add(arg);
		return arg;
	}

	/**
	 * Getter for {@link OverthereExecute#host}.
	 */
	public String getHost() {
		return host;
	}

	/**
	 * Host to connect to.
	 * @ant.required
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/**
	 * Getter for {@link OverthereExecute#username}.
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Username to connect as.
	 * @ant.required
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * Getter for {@link OverthereExecute#password}.
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * Password for user.
	 * @ant.required
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	/**
	 * Getter for {@link OverthereExecute#os}.
	 */
	public String getOs() {
		return os;
	}

	/**
	 * Operating system type. Either UNIX or WINDOWS. Default is WINDOWS.
	 */
	public void setOs(String os) {
		this.os = os;
	}

	/**
	 * Getter for {@link OverthereExecute#domain}.
	 */
	public String getDomain() {
		return domain;
	}

	/**
	 * Domain to login to. This must be Fully Qualified domain name, i.e. my.domain.com.
	 */
	public void setDomain(String domain) {
		this.domain = domain;
	}

	/**
	 * Getter for {@link OverthereExecute#dir}.
	 */
	public String getDir() {
		return dir;
	}

	/**
	 * Directory to change to. This must be in OS form, i.e. C:\ in Windows, / on Unix.
	 */
	public void setDir(String dir) {
		this.dir = dir;
	}

	/**
	 * Getter for {@link OverthereExecute#exec}.
	 */
	public List<OverthereArgument> getExec() {
		return exec;
	}

	/**
	 * Setter for {@link OverthereExecute#exec}.
	 */
	public void setExec(List<OverthereArgument> exec) {
		this.exec = exec;
	}

	/**
	 * Getter for {@link OverthereExecute#file}.
	 */
	public File getFile() {
		return file;
	}

	/**
	 * Local file to copy. Optional. Contents of file can also be specified using nested &lt;content&gt;...&lt;/content&gt; tag.
	 */
	public void setFile(File file) {
		this.file = file;
	}

	/**
	 * Getter for {@link OverthereExecute#toFile}.
	 */
	public String getToFile() {
		return toFile;
	}

	/**
	 * Specifies host file to copy. If <code>file=</code> is not set then this will be set to file name in temp dir.
	 * If <code>contents</code> is set, then this is <b>required</b>.
	 */
	public void setToFile(String toFile) {
		this.toFile = toFile;
	}

	/**
	 * Getter for {@link OverthereExecute#overwrite}.
	 */
	public boolean isOverwrite() {
		return overwrite;
	}

	/**
	 * Overwrite file is it newer than local file. Default is false.
	 */
	public void setOverwrite(boolean overwrite) {
		this.overwrite = overwrite;
	}

	/**
	 * Getter for {@link OverthereExecute#encoding}.
	 */
	public String getEncoding() {
		return encoding;
	}

	/**
	 * Encoding for contents. Default is UTF-8.
	 */
	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	/**
	 * Getter for {@link OverthereExecute#content}.
	 */
	public StringBuffer getContent() {
		return content;
	}

	/**
	 * Content for file. If this is specified (either by using attribute or by using nested &lt;content&gt;...&lt;/content&gt; tag),  
	 * then <code>file=</code> must not be specified.
	 */
	public void setContent(StringBuffer content) {
		this.content = content;
	}

	/**
	 * Getter for {@link OverthereExecute#temporary}.
	 */
	public boolean isTemporary() {
		return temporary;
	}

	/**
	 * Specifies whether file copied is temporary. If temporary file copied will be deleted after execution. 
	 * If &lt;cmd&gt; or &lt;arg&gt; nested tags is specified, file will be temporary by default, 
	 * if only file=, toFile=... is specified, temporary is <code>false</code>.
	 */
	public void setTemporary(boolean temporary) {
		this.temporary = temporary;
		this.temporarySet = true;
	}

	/**
	 * Getter for {@link OverthereExecute#https}.
	 */
	public boolean isHttps() {
		return https;
	}

	/**
	 * Specifies whether is to use HTTPS for WinRM connection. Default is false. 
	 */
	public void setHttps(boolean https) {
		this.https = https;
	}

	/**
	 * Getter for {@link OverthereExecute#locale}.
	 */
	public String getLocale() {
		return locale;
	}

	/**
	 * Locale for WinRM. If not specified, will be set by overthere.
	 */
	public void setLocale(String locale) {
		this.locale = locale;
	}

	/**
	 * Getter for {@link OverthereExecute#retry}.
	 */
	public int getRetry() {
		return retry;
	}

	/**
	 * Number of retries to establish connection. Usefull if machine is VM and recently have been created.
	 */
	public void setRetry(int retry) {
		this.retry = retry;
	}

	/**
	 * Getter for {@link OverthereExecute#retrySleep}.
	 */
	public long getRetrySleep() {
		return retrySleep;
	}

	/**
	 * Sleep period in milliseconds between retry timeouts. Default is 5 seconds.
	 */
	public void setRetrySleep(long retrySleep) {
		this.retrySleep = retrySleep;
	}

	/**
	 * Getter for {@link OverthereExecute#retryMatch}.
	 */
	public String getRetryMatch() {
		return retryMatch;
	}

	/**
	 * Exception text match to retry. Default is <code>Response code was 401</code>.
	 * Also will retry implicitly on <code>java.net.ConnectionException</code>.
	 */
	public void setRetryMatch(String retryMatch) {
		this.retryMatch = retryMatch;
	}

	/**
	 * Timeout in seconds for execution. If not set, then default is in effect, which is 60 seconds.
	 */	
	public int getTimeout() {
		return timeout;
	}

	/**
	 * Timeout in seconds for execution. If not set, then default is in effect, which is 60 seconds.
	 */	
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}
	
	/**
	 * Fail on execution returning anything other than 0. Defaults to true.
	 * @param failOnError
	 */
	public void setFailOnError(boolean failOnError) {
		this.failOnError = failOnError;
	}

	/**
	 * Fail on execution returning anything other than 0. Defaults to true.
	 */
	public boolean isFailOnError() {
		return failOnError;
	}

	/**
	 * Property to set to execution result.
	 * @param resultProperty
	 */
	public void setResultProperty(String resultProperty) {
		this.resultProperty = resultProperty;
	}
	
	/**
	 * Property to set to execution result.
	 */
	public String getResultProperty() {
		return resultProperty;
	}
	
	/**
	 * Sets jumpstation options. This property contains the connection options used
	 * to connect to an SSH jumpstation, which used afterwards.
	 * 
	 * @param jumpstation
	 */
	public void setJumpstation(String jumpstation) {
		this.jumpstation = jumpstation;
	}
	
	/**
	 * Getter for {@link OverthereExecute#jumpstation}.
	 */
	public String getJumpstation() {
		return jumpstation;
	}

	/**
	 * Getter for {@link #downloadFile}.
	 */
	public String getDownloadFile() {
		return downloadFile;
	}

	/**
	 * Downloads file from remote location after execution.
	 */
	public void setDownloadFile(String downloadFile) {
		this.downloadFile = downloadFile;
	}

	/**
	 * Getter for {@link #downloadTo}.
	 */
	public String getDownloadTo() {
		return downloadTo;
	}

	/**
	 * Sets filename for downloaded file.
	 * If not specified, name of file used from {@link #downloadFile}. 
	 * Can specify a folder.
	 */
	public void setDownloadTo(String downloadTo) {
		this.downloadTo = downloadTo;
	}
}
