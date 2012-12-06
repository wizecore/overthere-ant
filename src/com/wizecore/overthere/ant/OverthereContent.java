package com.wizecore.overthere.ant;

/**
 * Collection of file content.
 * 
 * @ant.task ignore="true"
 * @author huksley
 */
public class OverthereContent {

	private OverthereExecute exec;
	
	public OverthereContent(OverthereExecute exec) {
		this.exec = exec;
	}

	public void addText(String text) {
		if (exec.getContent() == null) {
			exec.setContent(new StringBuffer());
		}
		
		exec.getContent().append(exec.getProject().replaceProperties(text));
	}
}
