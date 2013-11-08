overthere-ant
=============

Ant tasks for xebialabs/overthere

Declaring task
==============

In build.xml specify:

  &lt;taskdef name="overexec" classpathref="taskclasspath" classname="com.wizecore.overthere.ant.OverthereExecute" /&gt;
  
Add to ''taskclasspath'' overthere-ant.jar and all otherthere dependencies (Look in release).
  
Example usage
=============

You can copy a file and execute it, or simply copy file.
Example 1 - copy and execute script.

  &lt;overexec host="testwinvm" username="Administrator" password="123" os="WINDOWS" file="install.bat">
    &lt;cmd>install.bat</cmd>
  &lt;/overexec>

