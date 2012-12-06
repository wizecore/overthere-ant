package com.wizecore.overthere.ant;

public class OverthereCmd {

	public static void main(String[] args) {
		OverthereExecute exec = new OverthereExecute();
		exec.setHost(args[0]);
		exec.setUsername(args[1]);
		exec.setPassword(args[2]);
		for (int i = 3; i < args.length; i++) {
			exec.createArg().setValue(args[i]);
		}
		exec.execute();
	}
}
