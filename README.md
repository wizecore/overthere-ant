overthere-ant
=============

Ant tasks for xebialabs/overthere

Declaring task
==============

In build.xml specify:

  <taskdef name="overexec" classpathref="taskclasspath" classname="com.wizecore.overthere.ant.OverthereExecute" />
  
Add to ''taskclasspath'' overthere-ant.jar and all otherthere dependencies (Look in release).
  
Example usage
=============

You can copy a file and execute it, or simply copy file.
Example 1 - copy and execute script.

  <overexec host="testwinvm" username="Administrator" password="123" os="WINDOWS" file="install.bat">
    <cmd>install.bat</cmd>
  </overexec>

