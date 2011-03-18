package org.mortbay.jetty.tests.policy.integration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;

/**
 * Basic executor for the testable Jetty Distribution.
 * <p>
 * Allows for a test specific directory, that is a copied jetty-distribution, and then modified for the test specific testing required.
 */
public class JettyProcess
{
    private File jettyHomeDir;
    private Process pid;

    /**
     * Setup the JettyHome as belonging in a testing directory associated with a testing clazz.
     * 
     * @param clazz
     *            the testing class using this JettyProcess
     * @throws IOException
     *             if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyProcess(Class<?> clazz) throws IOException
    {
        this.jettyHomeDir = MavenTestingUtils.getTargetTestingDir(clazz,"jettyHome");
        copyBaseDistro();
    }

    /**
     * Setup the JettyHome as belonging to a specific testing method directory
     * 
     * @param testdir
     *            the testing directory to use as the JettyHome for this JettyProcess
     * @throws IOException
     *             if unable to copy unpacked distribution into place for the provided testing directory
     */
    public JettyProcess(TestingDir testdir) throws IOException
    {
        this.jettyHomeDir = testdir.getDir();
        copyBaseDistro();
    }

    /**
     * 
     * @throws IOException
     *             if unable to copy unpacked distribution into place for the provided testing directory
     */
    private void copyBaseDistro() throws IOException
    {
        // The outputDirectory for the maven side dependency:unpack goal.
        File distroUnpackDir = MavenTestingUtils.getTargetFile("jetty-distro");
        PathAssert.assertDirExists("jetty-distribution dependency:unpack",distroUnpackDir);

        // The actual jetty-distribution-${version} directory is under this directory.
        // Lets find it.
        File subdirs[] = distroUnpackDir.listFiles(new FileFilter()
        {
            Pattern pat = Pattern.compile("jetty-distribution-[0-9]+\\.[0-9A-Z.-]*");

            public boolean accept(File path)
            {
                if (!path.isDirectory())
                {
                    return false;
                }

                Matcher mat = pat.matcher(path.getName());
                return mat.matches();
            }
        });

        if (subdirs.length == 0)
        {
            // No jetty-distribution found.
            StringBuilder err = new StringBuilder();
            err.append("No target/jetty-distro/jetty-distribution-${version} directory found.");
            err.append("\n  To fix this, run 'mvn process-test-resources' to create the directory.");
            throw new IOException(err.toString());
        }

        if (subdirs.length != 1)
        {
            // Too many jetty-distributions found.
            StringBuilder err = new StringBuilder();
            err.append("Too many target/jetty-distro/jetty-distribution-${version} directories found.");
            for (File dir : subdirs)
            {
                err.append("\n  ").append(dir.getAbsolutePath());
            }
            err.append("\n  To fix this, run 'mvn clean process-test-resources' to recreate the target/jetty-distro directory.");
            throw new IOException(err.toString());
        }

        File distroSrcDir = subdirs[0];
        FS.ensureEmpty(jettyHomeDir);
        System.out.printf("Copying Jetty Distribution: %s%n",distroSrcDir.getAbsolutePath());
        System.out.printf("            To Testing Dir: %s%n",jettyHomeDir.getAbsolutePath());
        IO.copyDir(distroSrcDir,jettyHomeDir);
    }

    /**
     * Return the $(jetty.home) directory being used for this JettyProcess
     * 
     * @return the jetty.home directory being used
     */
    public File getJettyHomeDir()
    {
        return this.jettyHomeDir;
    }

    /**
     * Copy a war file from ${project.basedir}/target/test-wars/${testWarFilename} into the ${jetty.home}/webapps/ directory
     * 
     * @param testWarFilename
     *            the war file to copy (must exist)
     * @throws IOException
     *             if unable to copy the war file.
     */
    public void copyTestWar(String testWarFilename) throws IOException
    {
        File srcWar = MavenTestingUtils.getTargetFile("test-wars/" + testWarFilename);
        File destWar = new File(jettyHomeDir,OS.separators("webapps/" + testWarFilename));
        IO.copyFile(srcWar,destWar);
    }

    /**
     * Delete a File or Directory found in the ${jetty.home} directory.
     * 
     * @param path
     *            the path to delete. (can be a file or directory)
     */
    public void delete(String path)
    {
        File jettyPath = new File(jettyHomeDir,OS.separators(path));
        FS.delete(jettyPath);
    }

    /**
     * Return the baseUri being used for this Jetty Process Instance.
     * 
     * @return the base URI for this Jetty Process Instance.
     */
    public URI getBaseUri()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Take the directory contents from ${project.basedir}/src/test/resources/${testConfigName}/ and copy it over whatever happens to be at ${jetty.home}
     * 
     * @param testConfigName
     *            the src/test/resources/ directory name to use as the source diretory for the configuration we are interested in.
     * @throws IOException
     *             if unable to copy directory.
     */
    public void overlayConfig(String testConfigName) throws IOException
    {
        File srcDir = MavenTestingUtils.getTestResourceDir(testConfigName);
        IO.copyDir(srcDir,jettyHomeDir);
    }

    /**
     * Start the jetty server
     * 
     * @throws IOException
     *             if unable to start the server.
     */
    public void start() throws IOException
    {
        List<String> commands = new ArrayList<String>();
        commands.add(getJavaBin());
        commands.add("-jar");
        commands.add("start.jar");

        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(jettyHomeDir);

        StringBuilder msg = new StringBuilder();
        msg.append("Executing:");
        for (String command : commands)
        {
            msg.append(" ");
            msg.append(command);
        }
        System.out.println(msg.toString());
        System.out.printf("Working Dir: %s%n",jettyHomeDir.getAbsolutePath());

        this.pid = pb.start();

        ConnectorParser connector = new ConnectorParser();

        startPump("STDOUT",connector,this.pid.getInputStream());
        startPump("STDERR",connector,this.pid.getErrorStream());

        try
        {
            long timeout = 60000;
            connector.wait(timeout);
            
            System.out.printf("Host is %s%n", connector.host);
            System.out.printf("Port is %d%n", connector.port);
        }
        catch (InterruptedException e)
        {
            pid.destroy();
            Assert.fail("Unable to find connector details within time limit");
        }
    }

    public static class ConnectorParser
    {
        private String host;
        private int port;
        private Pattern pat = Pattern.compile("[A-Za-z]*Connector@([0-9]*\\.[0-9]*\\.[0-9]*\\.[0-9]*):([0-9]*)");

        public void parse(String line)
        {
            Matcher mat = pat.matcher(line);
            if (mat.find())
            {
                host = mat.group(1);
                port = Integer.parseInt(mat.group(2));
                notify();
            }
        }
    }

    private void startPump(String mode, ConnectorParser connector, InputStream inputStream)
    {
        ConsoleStreamer pump = new ConsoleStreamer(mode,inputStream);
        pump.setParser(connector);
        Thread thread = new Thread(pump,"ConsoleStreamer/" + mode);
        thread.start();
    }

    private String getJavaBin()
    {
        String javaexes[] = new String[]
        { "java", "java.exe" };

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir,OS.separators("bin/" + javaexe));
            if (javabin.exists() && javabin.canExecute())
            {
                return javabin.getAbsolutePath();
            }
        }

        Assert.fail("Unable to find java bin");
        return "java";
    }

    /**
     * Stop the jetty server
     */
    public void stop()
    {
        System.out.println("Stopping JettyProcess ...");
        if (pid != null)
        {
            // TODO: maybe issue a STOP instead?
            pid.destroy();
        }
    }

    /**
     * Simple streamer for the console output from a Process
     */
    public static class ConsoleStreamer implements Runnable
    {
        private String mode;
        private BufferedReader reader;
        private ConnectorParser parser;

        public ConsoleStreamer(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
        }

        public void setParser(ConnectorParser connector)
        {
            this.parser = connector;
        }

        public void run()
        {
            String line;
            System.out.printf("ConsoleStreamer/%s initiated%n",mode);
            try
            {
                while ((line = reader.readLine()) != (null))
                {
                    if (parser != null)
                    {
                        parser.parse(line);
                    }
                    System.out.println(line);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            finally
            {
                IO.close(reader);
            }
            System.out.printf("ConsoleStreamer/%s finished%n",mode);
        }
    }
}