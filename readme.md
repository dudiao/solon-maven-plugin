# solon-maven-plugin

#### 打包插件打包前记得Clean

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.noear.solon.maven.plugin</groupId>
                <artifactId>solon-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
                <configuration>
                    <mainClass>solon.test.App</mainClass>
                </configuration>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
```