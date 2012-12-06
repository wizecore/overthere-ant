package com.wizecore.overthere.ant;

/**
 * Collection of arguments to command.
 * 
 * @ant.task ignore="true"
 * @author huksley
 */
public class OverthereArgument {

	String value = "";

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
	
	public void addText(String value) {
		this.value += value;
	}
}
