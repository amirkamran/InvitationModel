# InvitationModel
Implementation of domain adaptation algorithm based on the paper "Latent Domain Translation Models in Mix-of-Domains Haystack" http://www.aclweb.org/anthology/C14-1182

This work was supported by "STW Open Technologieprogramma" grant under project name "Data-Powered Domain-Specific Translation Services On Demand".

### Compilation

`mvn package`

This will generate target/invitationmodel-1.0.jar


### Usage

```
java -cp target/invitationmodel-1.0.jar nl.uva.illc.dataselection.InvitationModel

-cin,--in-domain-corpus <arg>     In-domain corpus name
-cmix,--mix-domain-corpus <arg>   Mix-domain corpus name
-i,--max-iterations <arg>         Maximum Iterations
-src,--src-language <arg>         Source Language
-trg,--trg-language <arg>         Target Language
 -th,--threshold <arg>             This threshold deicdes which sentences
                                   updates translation tables. Default is
                                   0.5
 -cf,--conv_threshold <arg>        This threshold decide if the
                                   convergence is reached. Default is
                                   0.00001
 ```

##### Example

If you have a parallel indomain corpus in-domain.l1, indomain.l2 and a parallel mix-domain corpus mixdomain.l1, mixdomain.l2.
Then you can execute this utility as follow:

`java -cp target/invitationmodel-1.0.jar nl.uva.illc.dataselection.InvitationModel -cin indomain -cmix mixdomain -src l1 -trg l2 -i 10 -th 0.5 -cf 0.00001`
