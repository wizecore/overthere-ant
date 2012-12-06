package com.wizecore.overthere.ant;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

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
import com.xebialabs.overthere.util.ConsoleOverthereProcessOutputHandler;

/**
 * Execution task for ant which working using SSH on Unix, WinRM on Windows.
 * See https://github.com/xebialabs/overthere for more info. 
 * 
 * @author ruslan
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
	boolean temporary = false;
	boolean https = false;
	String locale = null;
	int retry = 10;
	long retrySleep = 5000;
	String retryMatch = ".*Response code was 401.*";
	
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
						log("Working directory " + this.dir);
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
							OverthereFile remote = wdir != null ? over.getFile(wdir, toFile) : over.getFile(toFile);
							log("Writing content to host " + toFile);
							byte[] bb = content.toString().getBytes(encoding);
							OutputStream os = remote.getOutputStream();
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
									
									over.execute(ConsoleOverthereProcessOutputHandler.consoleHandler(), c);
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

	/**
	 * Getter for {@link OverthereExecute#host}.
	 */
	public String getHost() {
		return host;
	}

	/**
	 * Setter for {@link OverthereExecute#host}.
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
	 * Setter for {@link OverthereExecute#username}.
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
	 * Setter for {@link OverthereExecute#password}.
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
	 * Setter for {@link OverthereExecute#os}.
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
	 * Setter for {@link OverthereExecute#domain}.
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
	 * Setter for {@link OverthereExecute#dir}.
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
	 * Setter for {@link OverthereExecute#file}.
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
	 * Setter for {@link OverthereExecute#toFile}.
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
	 * Setter for {@link OverthereExecute#overwrite}.
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
	 * Setter for {@link OverthereExecute#encoding}.
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
	 * Setter for {@link OverthereExecute#content}.
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
	 * Setter for {@link OverthereExecute#temporary}.
	 */
	public void setTemporary(boolean temporary) {
		this.temporary = temporary;
	}

	/**
	 * Getter for {@link OverthereExecute#https}.
	 */
	public boolean isHttps() {
		return https;
	}

	/**
	 * Setter for {@link OverthereExecute#https}.
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
	 * Setter for {@link OverthereExecute#locale}.
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
	 * Setter for {@link OverthereExecute#retry}.
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
	 * Setter for {@link OverthereExecute#retrySleep}.
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
	 * Setter for {@link OverthereExecute#retryMatch}.
	 */
	public void setRetryMatch(String retryMatch) {
		this.retryMatch = retryMatch;
	}
}
