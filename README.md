# 〽️Optaplanner算法替换文档

+ 👤author: [@Zerui Wang](https://github.com/xinwuyun)、[@Yunqi Sun](https://github.com/Holmes233666)
+ 🗞️version2.0
+ 📅2021年12月13日

## 一、概要描述

本文档主要讲解在optaplanner代码中进行算法替换的流程和方法。

### 1.1 数据字典

| 序号 | 名称            | 含义                                                         |
| ---- | --------------- | :----------------------------------------------------------- |
| 1    | solution        | 问题的解决方案，一个规划问题有很多解决方案，算法的目的就是得到一个最优或次优的解决方案 |
| 2    | constraints     | 问题约束，表示对问题约束条件，同时也是计算solution的score的规则。数学上一般是很多不等式 |
| 3    | workingSolution | 工作解决方案（当前），问题搜索最佳解决方案过程中经过的solution |
| 4    | bestSolution    | 最佳解决方案（当前），问题求解过程中对当前得分最高的solution的称呼 |
| 5    | solver          | 问题求解器，可以通过配置文件进行配置，配置内容包括算法配置和顺序、终止条件、随机数设置等 |
| 6    | phase           | 问题“求解阶段”，一般一个问题的求解会有多个阶段，每个阶段使用一种算法 |
| 7    | score           | 一个solution的得分，分数计算规则的在contraints中定义         |
| 8    | bestScore       | bestSolution的得分（score）                                  |
| 9    | domain model    | 领域模型，对规划问题的定义，包含若干特定属性——问题事实（problem fact），计划实体、计划变量等 |
| 10   | move            | 从一个solution到另一个solution的改变                         |
| 11   | 邻域            | 一个solution经过**一个move**所能到达的**所有solution的集合** |

### 1.2 主要类

​      optaplanner代码中多用工厂设计模式，template method等。

+ 这一小节主要帮助理解后面的算法替换
+ 实际的算法替换过程不一定用得上其中的某些类，因为optaplanner会对他们进行操作，不用算法进行操作

#### 1.2.1 domain类

​      包括如下类

+ **计划实体**（planning entity），问题求解过程中可以改变的类
+ **计划变量**（panning variable），规划实体类的属性(或属性) ，在解决过程中发生变化。在此示例中，它是类 Process 上的属性计算机。
+ **影子变量**（shadow variable），如果存在多个关系和字段是计划变量，那么可能存在影子变量，它的值基于一个或多个**计划变量**计算得来
+ **问题事实**（problem fact），optaplanner无法改变的输入数据，一般是规划实体的数量等
+ **问题**（planning solution），解决方案类

#### 1.2.2 `solverConfig`类

​      问题求解器配置，有两种创建方式

+ 直接在代码中嵌入

  ```java
  SolverFactory<CloudBalance> solverFactory = SolverFactory.create(new SolverConfig()
                  .withSolutionClass(CloudBalance.class)
                  .withEntityClasses(CloudProcess.class)
                  .withConstraintProviderClass(CloudBalancingConstraintProvider.class)
                  .withTerminationSpentLimit(Duration.ofMinutes(2)));
  ```

+ 基于xml配置文件定义

  ```xml
  <solver>
    <randomSeed>0</randomSeed>
   <solutionClass>org.optaplanner.examples.nqueens.domain.NQueens</solutionClass>
    <entityClass>org.optaplanner.examples.nqueens.domain.Queen</entityClass>
    <scoreDirectorFactory>
      <constraintProviderClass>org.optaplanner.examples.nqueens.score.NQueensConstraintProvider</constraintProviderClass>
      <initializingScoreTrend>ONLY_DOWN</initializingScoreTrend>
    </scoreDirectorFactory>
  
    <constructionHeuristic>
  	...
    </constructionHeuristic>
    <localSearch>
  	...
    </localSearch>
  </solver>
  
  ```

​      solverCofig中主要包含如下定义

+ **Score configuration**：分数配置，包括硬约束和软约束。optaplanner支持多种实现方式（java se，ConstraintStreams，drools引擎）

+ **Domain model configuration**，领域模型配置（What optaplanner can change）

  ```xml
  <solutionClass>org.optaplanner.examples.cloudbalancing.domain.CloudBalance</solutionClass>
  <entityClass>org.optaplanner.examples.cloudbalancing.domain.CloudProcess</entityClass>
  ```

+ **Optimization algorithms configuration**，算法配置。包含求解使用算法类型和算法属性和参数配置。**我们的替换算法**的配置方式后面会讲解

  ```xml
  <!-- 局部搜索算法的配置 --> 
  <localSearch>
    <!-- 终止条件配置 --> 
      <termination>
        <bestScoreLimit>0</bestScoreLimit>
      </termination>
    <!-- moveSelector配置 -->
      <unionMoveSelector>
        <changeMoveSelector/>
        <swapMoveSelector/>
        <pillarChangeMoveSelector/>
        <pillarSwapMoveSelector/>
      </unionMoveSelector>
    <!-- 禁忌搜索配置 -->
      <acceptor>
        <entityTabuSize>7</entityTabuSize>
      </acceptor>
    </localSearch>
  <!-- 自定义算法配置 -->
  <customPhase>-->
      <customPhaseCommandClass>      org.optaplanner.core.impl.phase.custom.NoChangeCustomPhaseCommand
  		</customPhaseCommandClass>
  </customPhase>
  ```

#### 1.2.3 move类

move是从$solution_A$到$solution_B$的变化。例如，下面的移动将皇后c从第0行改到第2行

![image-20211201171735465](/Users/xinwuyun/Desktop/1.png)

+ `move`

这里仅介绍基本定义，optaplanner还定义了更多的move种类，比如swapmove

![image-20211202114016079](https://i.loli.net/2021/12/02/ixpdu8e6UPw4kNr.png)

更详细内容请参考官方文档：https://www.optaplanner.org/docs/optaplanner/latest/move-and-neighborhood-selection/move-and-neighborhood-selection.html#genericMoveSelectors

#### 1.2.4 moveSelector类

MoveSelector 的主要功能是在需要的时候创建 `Iterator<move>` 。即邻域中的move，优化算法将循环遍历这些move所到达的solutions。

> 这个不是算法的必要部分，但是是一个重要且好用的轮子，可配置性强，optaplanner自带算法大部分都用到该轮子。

详细内容参考，https://www.optaplanner.org/docs/optaplanner/latest/move-and-neighborhood-selection/move-and-neighborhood-selection.html#genericMoveSelectors

> 文档详细介绍了move和moveSelector的配置方法，这里没必要过于详细描述。第三部分的示例中会使用

![image-20211201173431203](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/01/7349f0b538a888dd84c881229e751f96-image-20211201173431203-42a5a3.png)

#### 1.2.5 phase类

问题求解阶段类，其抽象类定义多个**生命周期接口**。

+ `solve`：算法主要逻辑
+ `calculateWorkingStepScore` 计算进行一次move得到的workingSolution的得分
+ `solvingStarted`：生命周期方法，`solver`求解开始之前调用
+ `solvingEnded`：生命周期方法，`solver`求解结束
+ `phaseStarted`：生命周期方法，
  + 一个算法求解开始时调用
  + 其中对一些类的实例进行初始化
+ `phaseEnded`：算法结束时调用
+ 其他

`solver`类中包含一个`phaseList`，`solver.solve()`中会逐个调用`phase.solve()`.

#### 1.2.6 scope类

+ `solverScope`
+ `phaseScope`
+ `stepScope`
+ `moveScope`

1.  solver会迭代运行多个phase（算法）
2.  一个phase会进行多个step
3.  进行一次step需要尝试多个move

这些形成了如下的嵌套关系，每个scope类中保留了每个步骤的**信息**，比如

+ moveScope中保存所进行的move实例
+ stepScope中会保存遍历邻域之后选择的move

> ⚠️我们替换算法不会用到这些类的全部功能，只会用到一部分，其中大部分是规定动作

![image-20211201210521947](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/01/206889a40271071cdd1722daf82e387d-image-20211201210521947-34860f.png)

## 二、算法替换方法

算法替换主要流程

![流程图1的副本](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/1eea4b4ce911fd51e88af51c43897c80-%E6%B5%81%E7%A8%8B%E5%9B%BE1%E7%9A%84%E5%89%AF%E6%9C%AC-4f59c8.svg)

替换算法主要逻辑（phase.solve()）编写流程

![流程图2](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/0e34f668352cf6b31a05ac267a34365e-%E6%B5%81%E7%A8%8B%E5%9B%BE2-e7bc86.svg)

### 2.1 配置文件的编写方式

#### 2.1.1 书写规则

`optaplanner-examples/src/main/resources/org/optaplanner/examples/`

​      各个示例的算法配置文件都在这个目录下，第一部分讲解solverConfig时说过，求解器（solver）可以由xml文件构建。我们以`nqueen`问题的`optaplanner-examples/src/main/resources/org/optaplanner/examples/nqueens/nqueensSolverConfig.xml`为例

​      在代码倒数第二行加入，如下代码

```java
<customPhase>
    <customPhaseCommandClass>
      org.optaplanner.core.impl.phase.custom.NoChangeCustomPhaseCommand
    </customPhaseCommandClass>
 </customPhase>
```

> 这是optaplanner给开发者预留的自定义算法接口

![image-20211201215853690](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/01/17d566efa75463a5b3febae3ccf4a07c-image-20211201215853690-a4a620.png)

#### 2.1.2 算法参数配置方法

我们也可以像其他算法一样进行一定的参数配置，

```xml
<customPhase>
  <customPhaseCommandClass>...MyCustomPhase</customPhaseCommandClass>
  <customProperties>
    <property name="mySelectionSize" value="5"/>
  </customProperties>
</customPhase>
```

这些参数可以在工厂类`optaplanner-core/src/main/java/org/optaplanner/core/impl/phase/custom/DefaultCustomPhaseFactory.java`里使用

```java
phaseConfig.getCustomProperties()
```

获取供**算法求解或配置算法**使用

### 2.2 算法替换模板

optaplanner给开发者预留了自定义算法接口，但是与我们的需求有一定差距。所以替换算法不能直接在原仓库进行代码增添，请克隆我们提供的仓库

```shell
git clone https://github.com/xinwuyun/optaplanner-alter.git
```

#### 2.2.1 算法替换位置

`optaplanner-core/src/main/java/org/optaplanner/core/impl/phase/custom`

算法替换在此处进行，文件夹下包含

![image-20211201223557867](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/01/e4d90528fa270536ca84a3867db4b206-image-20211201223557867-9bfe30.png)

我们主要的工作可以只在这个文件夹下进行

> 如果完全按照opta的设计模式，则也需要在`/optaplanner-core/src/main/java/org/optaplanner/core/config`文件夹下进行相应编写，这样完全可以，但认为过于繁琐。

#### 2.2.2 算法类（DefaultCustomPhase）

**算法主要逻辑**放在DefaultCustomPhase.java的`solve`方法中即可，修改好配置文件后，optaplanner会自动调用

![image-20211201224132394](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/01/e840ec6797c0d9d14f1cb3b0ebd7ea88-image-20211201224132394-611484.png)

#### 2.2.3 工厂类说明

​      工厂类即该目录下的`DefaultCustomPhase.java`，工厂类创建中的各个组件（属性）将他们组装成`DefaultCustomPhase`。

​      当我们想要在`DefaultCustomPhase`中**添加某个组件（属性）时**，按照如下步骤进行

1. 首先肯定定义该类（及其工厂类），如果这个类是已有的**轮子**则不用定义
2. 在`DefaultCustomPhase`中引入并在类中添加该属性及其getter、setter方法
3. 在`DefaultCustomPhaseFactory`中的buildPhase方法中创建这个类的实例，调用setter方法。

### 2.3 算法输入

整体的求解器的输入是**问题定义**，optaplanner会将问题定义转换为算法的输入。

算法主要逻辑在`phase.solve()`，传入的参数是`solverScope`。

![image-20211201225154104](https://gitee.com/xinwuyun/myimage/raw/master/img/9f6704ddbe4e1caabe87abf880780873-image-20211201225154104-f6f187.png)

`solverScope`中包含**算法求解必须的所有资料**。opta提供了诸多接口供算法使用。所以从更抽象的角度来理解输入如下：

1. **当前solution**，即当前解决方案，算法会对这个solution进行优化
2. **scoreDirector**：用于计算分数

> 这里不理解请继续看下面两个小节

### 2.4 算法输出

算法输出是优化后的bestSolution和bestScore

> 算法实际上不会返回某个值，但是会在算法进行过程中**对solverScope中的bestScore和bestSolution直接进行修改**（前提是算法发现了更优解）

### 2.5 算法进行

​      对于启发式算法，算法的主要求解流程

1. 对邻域（当前solution下所有move）进行遍历
2. 依据各个move得到的新的solution的得分（score）和算法规则，挑选出*合适的*move
3. 对当前bestSolution进行该move得到新的bestSolution
4. 不断执行该步骤直到满足算法的**终止条件**（比如达到局部最优解、10步之内没有得到更优解、得到了分数大于0的solution、求解时长超过2分钟等）

对于其中的每一步，opta都提供了相应的接口来实现

#### 2.5.1 对邻域进行遍历

使用moveSelector，在第三部分中有应用的示例。

```java
for(Move<Solution_> move : moveSelector){

}
```

#### 2.5.2 依据各个move得到的新的solution的得分（score）和算法规则，挑选出*合适的*move

得分使用scoreDirector获取，scoreDirector通过下面的代码调用

```java
InnerScoreDirector<Solution_, ?> scoreDirector = solverScope.getScoreDirector();
```

调用`move.doMove(scoreDirector);`可以在workingSolution上进行改变，并返回一个move的反动作，再调用`scoreDirector.calculateScore()`可以得到分数。

最后，调用`undoMove.doMove(scoreDirector)`可以将`workingSolution`退回到初始的状态。

> 注意，这里所谓的workingSolution在scoreDirector实例中维护，开发者不必关心

```java
Move<Solution_> undoMove = move.doMove(scoreDirector);
Score score = scoreDirector.calculateScore();
undoMove.doMove(scoreDirector);
```

#### 2.5.3 对当前bestSolution进行该move得到新的bestSolution

遍历过邻域后，算法能够得到一个move作为nextStep，同时获得它的分数。

进行如下调用，更新bestSolution和相关属性。

```java
stepScope.setScore(maxScore);
doStep(stepScope, nextStep);
stepEnded(stepScope);
phaseScope.setLastCompletedStepScope(stepScope);
```

`doStep`的内容如下

```java
protected void doStep(CustomStepScope<Solution_> stepScope, Move<Solution_> step) {
        Move<Solution_> undoStep = step.doMove(stepScope.getScoreDirector());
        predictWorkingStepScore(stepScope, step);
        solver.getBestSolutionRecaller().processWorkingSolutionDuringStep(stepScope);
    }
```

#### 2.5.4 不断执行该步骤直到满足算法的**终止条件**

算法的终止将在**下一小节讲解**，接口如下。

```
while(!phaseTermination.isPhaseTerminated(phaseScope))
```

> 也就是说本文档会对启发式算法的替换用处更大，如果是实现其他算法，比如粒子群算法，本小节**算法的进行**可能会有一定的变化

### 2.6 算法终止(Termination)

#### 2.6.1 代码中调用

​      上面说了算法会不断进行直到*当前状态（运行时间、bestScore等）*满足算法的终止条件，optaplanner提供了相应接口，我们可以在配置文件中对termination条件进行配置，在算法代码中只需要调用如下代码即可得知算法是否达到终止条件

```java
phaseTermination.isPhaseTerminated(phaseScope)
```

![image-20211202094715511](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/4f8b9e2de083f729400578c435df474f-image-20211202094715511-15f72b.png)

#### 2.6.2 配置文件配置方式

##### （1）可配置条件

+ 达到一定时间时停止
+ 一定时间内没有**最优解更新**
+ 达到需要的分数
+ 走过的step数超过一定值
  + 走过的step数表示2.5中步骤2、3的重复次数
+ 一定step数内没有**最优解更新**
  + 注意和*一定时间内*区分
+ 其他（不常用）

##### （2）配置编写规则和编写位置

在配置文件中有两种添加方式

+ 全局termination

  + ![ji](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/f970e1fd42feac7032124a4ffdd41bdb-image-20211202094434707-9da6b5.png)

  + 写在相应算法中，

  + > 注意，必须作为算法的第一个子标签

    ![image-20211202094512799](/Users/xinwuyun/Library/Application Support/typora-user-images/image-20211202094512799.png)

配置规则书写见官方文档，链接和目录如下

https://docs.optaplanner.org/8.11.1.Final/optaplanner-docs/html_single/index.html#termination

![image-20211202091428417](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/1912eb1777a0ddb3d5fb5b02c80dca09-image-20211202091428417-c3ad1c.png)

### 2.7 可用的轮子

#### 2.7.1 MoveSelector

MoveSelector前面已经讲解过，MoveSelector 的主要功能是在需要的时候创建 `Iterator<move>` 。优化算法将循环遍历这些步骤的子集。

> moveSelector是一个在optaplanner提供的其他算法中经常用的工具

在第三部分的实例中，我们会实践在算法中使用他，这里不细说。

## 三、算法替换示例

### 3.1 克隆仓库

> + 该仓库基于仓库进行了一定修改
> + 主分支是等待进行算法替换的分支
> + `finish`分支是完成本文算法替换的分支

```shell
git clone https://github.com/xinwuyun/optaplanner-alter.git
```

**接下来的文档基于主分支(main)进行**，也可以使用如下命令切换到`finish`分支直接查看完成后的代码

```shell
git checkout finish
```

### 3.2 尝试运行示例

运行任一示例，以nqueen为例，在IDE中做好运行配置

> 如果是idea，设置workingDirectory为`/Users/xinwuyun/Documents/code/optaplanner/optaplanner/optaplanner-examples`
>
> ![image-20211121144849877](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/d54324dd10dd9f747cb031d1e4c765dc-d54324dd10dd9f747cb031d1e4c765dc-image-20211121144849877-a60ed1-932f5a.png)

运行`optaplanner-examples/src/main/java/org/optaplanner/examples/nqueens/app/NQueensHelloWorld.java`，看一下输出

最后能看到如下结果

![image-20211122142901212](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/11/22/24f410eae7e4a471f9700b60b3b16fb6-image-20211122142901212-d44baf.png)

结尾展示了棋盘的排布，算法先后使用了构造启发式算法和禁忌搜索

### 3.3 要实现的算法

作为示例，这里实现最简单的**贪心算法**

1. 探索初始状态的邻域K
2. 在K中选择得分最高的状态；
3. 若此状态得分大于当前，则选择此状态为下一个状态；否则算法停止；
4. 重复2，3直至最优或算法停止

### 3.4 开始算法替换

#### 3.4.1 开始之前

`solver`的配置保存在一个`xml`文件中。`nqueen`的`solver`配置保存在`org/optaplanner/examples/nqueens/nqueensSolverConfig.xml`中。

> 这个路径在`helloWorld.java`指定![image-20211121151040343](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/7b176f35c76dd261037095e7978a16a3-7b176f35c76dd261037095e7978a16a3-image-20211121151040343-6a2f49-b7efba.png)

xml文件内容如下：

![image-20211122143007756](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/acdcde861f721e7d995b47afcb7ae15f-acdcde861f721e7d995b47afcb7ae15f-image-20211122143007756-ed68f4-6dbee8.png)

通过配置该文件，可以定义运行示例时使用什么算法和算法次序。这里先后定义了两个算法：1. 构造启发式；2. 局部搜索算法。

两个算法的代码分别在

![image-20211121170813870](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/d0fdcb277d9eceba4f70e54b59e0f65e-d0fdcb277d9eceba4f70e54b59e0f65e-image-20211121170813870-ec0f95-34485c.png)

示例运行过程中，`optaplanner`对于每个算法会首先构建对应的**Factory类**，再`build`每一个算法的实例。使用算法时，调用`***Phase.solve`。

---

**开始替换算法**

#### 3.4.2 修改配置文件

在`nqueensSolverConfig.xml`中的倒数第二行下方添加如下标签，注释掉LocalSeach算法配置

```xml
<customPhase>
  <customPhaseCommandClass>
    org.optaplanner.core.impl.phase.custom.NoChangeCustomPhaseCommand
   </customPhaseCommandClass>
</customPhase>
```

接下来的工作主要围绕这里进行`optaplanner-core/src/main/java/org/optaplanner/core/impl/phase/custom`。

![image-20211121164349602](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/11/21/b6fdfc44cb99c7d6d1f03c78c7d4b050-image-20211121164349602-cc6a30.png)

这里有最基础的代码。我们需要在上面增加我们需要的部分

#### 3.4.3 创建moveSelector

1. 在`DefaultCustomPhase.java`中定义一个moveSelector

在类中添加如下代码

```java
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;

protected MoveSelector<Solution_> moveSelector;

public MoveSelector<Solution_> getMoveSelector() {
  return moveSelector;
}

public void setMoveSelector(MoveSelector<Solution_> moveSelector){
  this.moveSelector = moveSelector;
}
```

2. 在`DefaultCustomPhaseFactory.java`创建moveSelector

![image-20211121170114619](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/11/21/b2454fb21918720d505fc90aaf38eaed-image-20211121170114619-f8692f.png)

添加如下方法

```java
import org.optaplanner.core.config.heuristic.selector.common.SelectionCacheType;
import org.optaplanner.core.config.heuristic.selector.common.SelectionOrder;
import org.optaplanner.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;
import org.optaplanner.core.impl.heuristic.selector.move.generic.ChangeMoveSelectorFactory;

protected MoveSelector<Solution_> buildMoveSelector(HeuristicConfigPolicy<Solution_> configPolicy) {
  			// 定义move的缓存类型 https://docs.optaplanner.org/8.9.1.Final/optaplanner-docs/html_single/index.html#generalSelectorFeatures
  			// JUST_IN_TIME表示不使用缓存
        SelectionCacheType defaultCacheType = SelectionCacheType.JUST_IN_TIME;
  			
  			// 定义实体和变量的选择次序
  			// https://docs.optaplanner.org/8.9.1.Final/optaplanner-docs/html_single/index.html#selectionOrder
  			// 按照默认次序（类似顺序）
        SelectionOrder defaultSelectionOrder = SelectionOrder.ORIGINAL;
        ChangeMoveSelectorConfig changeMoveSelectorConfig = new ChangeMoveSelectorConfig();
        MoveSelector<Solution_> moveSelector = new ChangeMoveSelectorFactory<Solution_>(changeMoveSelectorConfig)
                .buildMoveSelector(configPolicy, defaultCacheType, defaultSelectionOrder);
        return moveSelector;
    }
}
```

在`buildPhase`方法中调用`buildMoveSelctor`得到`moveSelector`示例，将其交给`phase`

```java
phase.setMoveSelector(buildMoveSelector(phaseConfigPolicy));
```

在`DefaultCustomPhase.java`中的`phaseStarted`和`phaseEnded`中分别添加

```java
moveSelector.phaseStarted(phaseScope);
```

和

```java
moveSelector.phaseEnded(phaseScope);
```

3. **在`solve`中编写算法逻辑**

![image-20211202105635126](https://i.loli.net/2021/12/02/Zf7C8KTbS2dsiN9.png)

我们在中间注释处编写算法逻辑

#### 3.4.4 判定算法终止

上面我们说过使用下面这段代码进行判断

```java
while(!phaseTermination.isPhaseTerminated(phaseScope))
```

本算法要求：如果在**邻域中没有发现更优解**，则算法停止，转换为配置文件中的语言为

```xml
<termination>
  <bestScoreLimit>0</bestScoreLimit>
  <unimprovedStepCountLimit>1</unimprovedStepCountLimit>
</termination>
```

上面的配置表示，当bestScore$\ge$0时算法停止;当有1个step内没有发现更优解时算法停止(即邻域内没有发现更优解）

![image-20211202111904958](https://i.loli.net/2021/12/02/6MJIir3l4GmkzPC.png)

```java
// 括号中的内容即可检查上图中的Termination是否成立
while(!phaseTermination.isPhaseTerminated(phaseScope)){
  
  // **********************************
  // 遍历邻域，从中挑选一个move作为nextstep
  // **********************************
  
}
```

#### 3.4.5 遍历邻域并计算每个状态的得分(主要逻辑)

先import

```java
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;
import java.util.ArrayList;
import java.util.Collections;
```

将下面代码添加到solve方法中间的注释处

```java
// scoreDirector用于计算得分
InnerScoreDirector<Solution_, ?> scoreDirector = solverScope.getScoreDirector();
while(!phaseTermination.isPhaseTerminated(phaseScope)){
  //******
  // 固定操作
  //******
  CustomStepScope<Solution_> stepScope = new CustomStepScope<>(phaseScope);
  
  // 用数组保存领域中和相应得分
  List<Move<Solution_>> moves = new ArrayList<>();
  List<Score> scores = new ArrayList<>();
	// 固定
  stepStarted(stepScope);
  
  // 遍历邻域
  for(Move<Solution_> move : moveSelector){
    // 进行move，会对当前solution进行修改
    // 返回一个move的反动作，方便回滚
    Move<Solution_> undoMove = move.doMove(scoreDirector);
    // 计算改变的solution得分
    Score score = scoreDirector.calculateScore();
    // 保存得分和move
    moves.add(move);
    scores.add(score);
    // 回滚
    undoMove.doMove(scoreDirector);
  }			
  
  // 选择最高得分的move
  Score maxScore = Collections.max(scores);
  int i = scores.indexOf(maxScore);
  Move<Solution_> nextStep = moves.get(i);
  // 将最高分存放到stepScope中
  stepScope.setScore(maxScore);
  

  //******
  // 固定操作
  //******
  doStep(stepScope, nextStep);
  stepEnded(stepScope);
  phaseScope.setLastCompletedStepScope(stepScope);
}
```

#### 3.4.6 编写doStep

`doStep`应在选定`nextStep`，并且`nextStep`可以接受之后调用

```java
protected void doStep(CustomStepScope<Solution_> stepScope, Move<Solution_> step) {
        Move<Solution_> undoStep = step.doMove(stepScope.getScoreDirector());
        predictWorkingStepScore(stepScope, step);
        solver.getBestSolutionRecaller().processWorkingSolutionDuringStep(stepScope);
    }
```

### 3.5 运行

运行`org.optaplanner.examples.nqueens.app.NQueensHelloWorld#main`

注意此时`nqueenSolverConfig.xml`，注释掉`<LocalSearch>`

![image-20211202111904958](https://i.loli.net/2021/12/02/6MJIir3l4GmkzPC.png)

输出如下

![image-20211202111720838](https://i.loli.net/2021/12/02/eaH3niWJ97M5rVu.png)

根据输出可以看到，替换算法进行了两步后到达局部最优，算法停止。

![image-20211202111742740](https://i.loli.net/2021/12/02/4QFOPipxovmgIqW.png)

> ![image-20211121215958319](https://cdn.jsdelivr.net/gh/xinwuyun/pictures@main/2021/12/02/5eff6d16290182ad57b6a0681949e99b-image-20211121215958319-86c322.png)
>
> 这些是构造器启发式算法的输出。由于前面我们配置文件中的`localSearch`算法，所以示例没有调用`localSearch`

## 四、如何替换更复杂的算法

比如如果我们要实现**禁忌搜索**，以避免陷入**局部最优解**。则可以在算法进行过程中维护一个`step`数组储存最近的若干`nextStep`，遍历邻域过程中增加一个`isAccepted(move/stepScope)`，筛选掉**禁忌move**。

> 禁忌搜索`optaplanner`已经实现了，代码可供参考

**封装**

上面的描述比较面向过程，较好的方式是定义决定器(`Decider`)，捕捉器`Forager`，接受器`Acceptor`。对于不同算法或者同一算法的不同变体，这些xx器有不同的**配置**和**实现**。

### 举例

比如，我们希望在我们替换的**贪婪算法**基础上实现**禁忌搜索**，则可以实现一个`Acceptor`，其中维护一个`step`队列，保存最近几个`step`，实现一个`isAccepted`方法。

在`customPhase`中创建一个`acceptor`实例，遍历邻域时，对每个`move`调用`accoptor.isAccepted(move)`

```java
while(!phaseTermination.isPhaseTerminated(phaseScope)){
  ...
	for(move : moveSelector){
    if(!accoptor.isAccepted(move)){
      continue;
    }
    ...
  }
  ...
}
```

**最佳实践参考optapanner实现的LocalSearch算法（及其变体）的实现**.
